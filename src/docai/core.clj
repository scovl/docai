(ns docai.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [docai.document :as doc]
            [docai.embedding :as emb]
            [docai.llm :as llm]
            [docai.pg :as pg]
            [docai.advanced-rag :as adv-rag]
            [docai.metrics :as metrics]
            [docai.agents :as agents]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:gen-class))

(def ^:private docs-path "resources/docs")
(def ^:private use-postgres (atom false))
(def ^:private use-advanced-rag (atom false))

(defn- load-documentation
  "Carrega todos os arquivos de documentação do diretório configurado"
  []
  (->> (file-seq (io/file docs-path))
       (filter #(.isFile %))
       (map #(.getPath %))))

(defn- get-file-content
  "Lê o conteúdo completo de um arquivo, retornando string vazia em caso de erro"
  [file-path]
  (try
    (slurp file-path)
    (catch Exception e
      (println "Erro ao ler arquivo:" file-path (.getMessage e))
      "")))

(defn setup-knowledge-base
  "Configura a base de conhecimento inicial extraindo e processando textos dos arquivos de documentação"
  []
  (let [doc-files (load-documentation)]
    (if (empty? doc-files)
      (println "Aviso: Nenhum arquivo de documentação encontrado em" docs-path)
      (doseq [file doc-files]
        (println "Arquivo encontrado:" file)))
    
    (let [all-chunks (mapcat doc/extract-text doc-files)
          processed-chunks (doc/preprocess-chunks all-chunks)]
      (println (str "Processando " (count processed-chunks) " chunks de texto..."))
      
      (when (and (seq processed-chunks) (< (count processed-chunks) 5))
        (println "DEBUG - Primeiros chunks:")
        (doseq [chunk (take 5 processed-chunks)]
          (println (str "Chunk: '" (subs chunk 0 (min 50 (count chunk))) "...'"))))
      
      (let [embeddings (emb/create-embeddings processed-chunks)]
        {:chunks processed-chunks
         :embeddings embeddings
         :original-files doc-files}))))

(defn query-rag
  "Processa uma consulta usando o pipeline RAG (Retrieval Augmented Generation)
   Recupera contexto relevante da base de conhecimento e gera uma resposta usando LLM"
  [knowledge-base query]
  (println "DEBUG - Processando query:" query)
  (if (and (seq (:chunks knowledge-base)) 
           (seq (:embeddings knowledge-base)))
    (let [query-emb (first (emb/create-embeddings [query]))
          similar-idxs (emb/similarity-search query-emb 
                                           (:embeddings knowledge-base)
                                           3)
          _ (println "DEBUG - Índices similares:" similar-idxs)
          
          ;; Obter contexto relevante
          context-chunks (->> similar-idxs
                              (map #(nth (:chunks knowledge-base) %))
                              (str/join "\n\n"))
          
          ;; Se não houver chunks relevantes, use o conteúdo original
          context (if (str/blank? context-chunks)
                    (if (seq (:original-files knowledge-base))
                      (get-file-content (first (:original-files knowledge-base)))
                      "Não foi possível encontrar informações relevantes.")
                    context-chunks)]
      
      (println "DEBUG - Tamanho do contexto:" (count context) "caracteres")
      (when (> (count context) 0)
        (println "DEBUG - Amostra do contexto:" 
                 (subs context 0 (min 200 (count context))) "..."))
      
      ;; Gerar resposta usando o LLM
      (llm/generate-response query context))
    "Não foi possível encontrar informações relevantes na base de conhecimento."))

(defn query-pg-rag
  "Processa uma consulta usando PostgreSQL com pgvector para busca semântica"
  [query]
  (println "DEBUG - Processando query no PostgreSQL:" query)
  
  ;; Verificamos primeiro se é uma consulta relacionada a JWT
  (let [lower-query (str/lower-case query)
        jwt-keywords ["jwt" "token" "autenticação" "auth" "json web token"]]
    
    ;; Se for relacionada a JWT, usamos uma abordagem específica primeiro
    (if (some #(str/includes? lower-query %) jwt-keywords)
      ;; Tentativa específica para consultas JWT
      (let [_ (println "DEBUG - Detectada consulta relacionada a JWT, usando busca especial")
            conn (jdbc/get-connection pg/db-spec)
            docs (try
                   (jdbc/execute! 
                     conn 
                     ["SELECT id, titulo, conteudo, categoria FROM documentos WHERE LOWER(conteudo) LIKE ? LIMIT 10"
                      "%jwt%"]
                     {:builder-fn rs/as-unqualified-maps})
                   (finally (.close conn)))]
        
        (if (seq docs)
          ;; Se encontrou documentos relacionados a JWT, use-os como contexto
          (let [context (->> docs
                           (map :conteudo)
                           (str/join "\n\n"))]
            (println "DEBUG - Encontrados" (count docs) "documentos relacionados a JWT")
            (llm/generate-response query context))
          
          ;; Se não encontrou por busca direta, tenta a busca semântica normal
          (let [_ (println "DEBUG - Nenhum resultado direto para JWT, tentando busca semântica")
                results (pg/semantic-search query 5)]
            (if (seq results)
              (let [;; Extrair contexto dos resultados
                    context (->> results
                               (map :conteudo)
                               (str/join "\n\n"))]
                (println "DEBUG - Resultados encontrados:" (count results))
                (llm/generate-response query context))
              "Não foi possível encontrar informações sobre JWT na base de conhecimento."))))
      
      ;; Para consultas não relacionadas a JWT, usamos o fluxo normal
      (let [results (pg/semantic-search query 5)]
        (if (seq results)
          (let [;; Extrair contexto dos resultados
                context (->> results
                           (map :conteudo)
                           (str/join "\n\n"))]
            (println "DEBUG - Resultados encontrados:" (count results))
            (println "DEBUG - Tamanho do contexto:" (count context) "caracteres")
            (when (> (count context) 0)
              (println "DEBUG - Amostra do contexto:" 
                       (subs context 0 (min 200 (count context))) "..."))
            (llm/generate-response query context))
          "Não foi possível encontrar informações relevantes na base de conhecimento.")))))

(defn query-advanced-rag
  "Processa uma consulta usando o pipeline RAG avançado"
  [query]
  (println "DEBUG - Processando query com RAG avançado:" query)
  (let [start-time (System/currentTimeMillis)
        ;; Verificar se a consulta precisa do workflow com agentes
        need-agents (agents/needs-agent-workflow? query)
        _ (when need-agents
            (println "DEBUG - Consulta identificada como complexa, usando workflow com agentes"))
        
        ;; Escolher o processamento adequado
        response (if need-agents
                   (agents/process-with-agents query)
                   (adv-rag/advanced-rag-query query))
        
        end-time (System/currentTimeMillis)
        latency (- end-time start-time)]
    
    ;; Registrar métricas
    (metrics/log-rag-interaction query [] response latency)
    
    ;; Avaliar qualidade da resposta (apenas para monitoramento)
    (future
      (try
        (let [context "Contexto não disponível na implementação atual"]
          (println "Avaliando qualidade da resposta em background...")
          (let [quality-score (metrics/evaluate-response-quality query context response)]
            (println "Score de qualidade da resposta:" (:faithfulness quality-score))))
        (catch Exception e
          (println "Erro ao avaliar qualidade da resposta:" (.getMessage e)))))
    
    response))

(defn import-docs-to-postgres
  "Importa documentos para o PostgreSQL"
  []
  (println "Importando documentos para o PostgreSQL...")
  (let [doc-files (load-documentation)]
    (if (empty? doc-files)
      (println "Aviso: Nenhum arquivo de documentação encontrado em" docs-path)
      (doseq [file doc-files]
        (pg/import-markdown-file! file "documentacao")))))

(defn process-file-dynamic
  "Processa um único arquivo com chunking dinâmico"
  [args]
  (let [file-path (first args)]
    (if (and file-path (.isFile (io/file file-path)))
      (do
        (println "Processando arquivo com chunking dinâmico:" file-path)
        (doc/process-with-dynamic-chunking file-path))
      (println "Arquivo inválido ou não encontrado:" file-path))))

(defn process-dir-dynamic
  "Processa um diretório completo com chunking dinâmico"
  [args]
  (let [dir-path (first args)]
    (if (and dir-path (.isDirectory (io/file dir-path)))
      (do
        (println "Processando diretório com chunking dinâmico:" dir-path)
        (doc/process-directory-with-dynamic-chunking dir-path))
      (println "Diretório inválido ou não encontrado:" dir-path))))

(defn display-help
  "Exibe a ajuda do DocAI"
  []
  (println "DocAI - RAG para Documentação Técnica\n")
  (println "Uso: docai [comando] [opções]\n")
  (println "Comandos disponíveis:")
  (println "--help                 Exibe esta mensagem de ajuda")
  (println "--version              Exibe a versão do DocAI")
  (println "--process <arquivo>    Processa um arquivo e adiciona à base de conhecimento")
  (println "--import <diretório>   Importa todos os arquivos de um diretório")
  (println "--postgres <query>     Usa o PostgreSQL para busca semântica")
  (println "--advanced <query>     Usa o RAG avançado para consultas")
  (println "--agents <query>       Usa explicitamente o workflow com agentes para consultas complexas")
  (println "--search <query>       Busca informações na base de conhecimento")
  (println "--search               Inicia modo interativo de busca")
  (println "--clean                Limpa dados em memória")
  (println "--metrics <dias>       Exibe métricas RAG dos últimos N dias")
  (println "--feedback <query-id>  Fornece feedback para uma consulta específica")
  (println "--process-dynamic <arquivo>    Processa um arquivo com chunking dinâmico")
  (println "--import-dynamic <diretório>   Importa diretório com chunking dinâmico"))

(defn display-version
  "Exibe a versão do sistema"
  []
  (println "DocAI v1.2.0 - Sistema de RAG Avançado com Chunking Dinâmico"))

(defn process-file
  "Processa um único arquivo"
  [args]
  (let [file-path (first args)]
    (if (and file-path (.isFile (io/file file-path)))
      (do
        (println "Processando arquivo:" file-path)
        (pg/import-markdown-file! file-path "documentacao"))
      (println "Arquivo inválido ou não encontrado:" file-path))))

(defn import-directory
  "Importa todos os documentos de um diretório"
  [args]
  (let [dir-path (first args)]
    (if (and dir-path (.isDirectory (io/file dir-path)))
      (let [files (->> (file-seq (io/file dir-path))
                      (filter #(.isFile %)))]
        (println "Importando" (count files) "arquivos do diretório:" dir-path)
        (doseq [file files]
          (println "Processando:" (.getName file))
          (pg/import-markdown-file! (.getPath file) "documentacao")))
      (println "Diretório inválido ou não encontrado:" dir-path))))

(defn search-docs
  "Realiza uma busca com a consulta fornecida"
  [query]
  (println "Resultado da busca para:" query)
  (if @use-advanced-rag
    (println (query-advanced-rag query))
    (if @use-postgres
      (println (query-pg-rag query))
      (println (query-rag (setup-knowledge-base) query)))))

(defn search-cli
  "Inicia o modo de busca interativo"
  []
  (println "Modo de busca interativo. Digite 'sair' para encerrar.")
  (try
    (loop []
      (print "Consulta> ")
      (flush)
      (when-let [input (read-line)]
        (if (= (str/lower-case input) "sair")
          (println "Saindo do modo de busca.")
          (do
            (search-docs input)
            (recur)))))
    (catch Exception e
      (println "Erro no modo de busca:" (.getMessage e)))))

(defn run-with-postgres
  "Inicia o sistema com suporte a PostgreSQL"
  [_]
  (reset! use-postgres true)
  (println "Configurando ambiente PostgreSQL para RAG...")
  ;; Configurar Ollama para usar o endereço do container
  (llm/set-ollama-docker-mode!)
  (pg/setup-pg-rag!)
  (import-docs-to-postgres)
  (println "PostgreSQL RAG pronto!")
  (search-cli))

(defn run-with-advanced-rag
  "Inicia o sistema com RAG avançado"
  [_]
  (reset! use-postgres true)
  (reset! use-advanced-rag true)
  (println "Configurando ambiente PostgreSQL para RAG avançado...")
  ;; Configurar Ollama para usar o endereço do container
  (llm/set-ollama-docker-mode!)
  (pg/setup-pg-rag!)
  (metrics/setup-metrics-tables!)
  (agents/setup-agent-tables!)
  (adv-rag/setup-advanced-rag!)
  (import-docs-to-postgres)
  (println "RAG avançado pronto!")
  (search-cli))

(defn clean-data
  "Limpa dados em memória e caches"
  []
  (println "Limpando caches e dados temporários...")
  (reset! adv-rag/embedding-cache {})
  (reset! adv-rag/response-cache {})
  (reset! agents/agent-cache {})
  (println "Caches limpos com sucesso!"))

(defn show-metrics
  "Exibe métricas do sistema RAG"
  [args]
  (let [days (if (seq args)
               (try
                 (Integer/parseInt (first args))
                 (catch Exception _ 7))
               7) ;; Padrão: últimos 7 dias
        end-date (java.util.Date.)
        start-date (-> (java.util.Calendar/getInstance)
                       (doto (.setTime end-date)
                             (.add java.util.Calendar/DAY_OF_MONTH (- days)))
                       (.getTime))]
    
    (println "Calculando métricas dos últimos" days "dias...")
    (let [metrics (metrics/calculate-rag-metrics start-date end-date)]
      (if metrics
        (do
          (println "=== Métricas do Sistema RAG ===")
          (println "Período:" start-date "até" end-date)
          (println "Total de consultas:" (:total_queries metrics))
          (println "Latência média:" (:avg_latency metrics) "ms")
          (println "Latência P95:" (:p95_latency metrics) "ms"))
        (println "Não foi possível calcular métricas para o período especificado.")))))

(defn provide-feedback
  "Permite ao usuário fornecer feedback para uma consulta específica"
  [args]
  (if-let [query-id (first args)]
    (try
      (let [query-id-int (Integer/parseInt query-id)]
        (metrics/collect-user-feedback query-id-int))
      (catch NumberFormatException e
        (println "ID de consulta inválido. Forneça um número inteiro válido:" (.getMessage e))))
    (println "Por favor, forneça o ID da consulta para a qual deseja dar feedback.")))

(defn query-with-agents
  "Processa uma consulta utilizando explicitamente o workflow com agentes"
  [args]
  (if-let [query (first args)]
    (let [response (agents/execute-agent-workflow query)]
      (println "\n------ Resposta ------")
      (println response)
      (println "-----------------------\n"))
    (println "Por favor, forneça uma consulta após o comando --agents")))

(defn setup-system
  "Configura todo o sistema DocAI na inicialização"
  []
  (println "Configurando o sistema DocAI...")
  
  ;; Verificar e configurar PostgreSQL
  (when (pg/check-postgres-connection)
    (pg/setup-pg-rag!)
    (metrics/setup-metrics-tables!)
    (agents/setup-agent-tables!))
  
  (println "Sistema DocAI configurado com sucesso!"))

(defn -main
  "Função principal do DocAI"
  [& args]
  ;; Inicializar o sistema
  (setup-system)
  
  (let [command (first args)
        rest-args (rest args)]
    (case command
      ;; Comandos existentes
      "--help" (display-help)
      "--version" (display-version)
      "--process" (process-file rest-args)
      "--import" (import-directory rest-args)
      "--postgres" (run-with-postgres rest-args)
      "--advanced" (run-with-advanced-rag rest-args)
      "--agents" (query-with-agents rest-args)
      "--search" (if (seq rest-args)
                   (search-docs (first rest-args))
                   (search-cli))
      "--clean" (clean-data)
      "--metrics" (show-metrics rest-args)
      "--feedback" (provide-feedback rest-args)
      
      ;; Novos comandos para chunking dinâmico
      "--process-dynamic" (process-file-dynamic rest-args)
      "--import-dynamic" (process-dir-dynamic rest-args)
      
      ;; Default
      (if (seq args)
        (println "Comando desconhecido:" command "\nUse --help para ver os comandos disponíveis.")
        (search-cli))))
  (shutdown-agents))

;; Exemplos de funções úteis para o REPL (copie e cole no REPL conforme necessário):
;; 
;; Para ativar o modo PostgreSQL:
;; (reset! docai.core/use-postgres true)
;; 
;; Para ativar o modo em memória:
;; (reset! docai.core/use-postgres false)
;; 
;; Para importar documentos para o PostgreSQL:
;; (docai.core/import-docs-to-postgres)
;; 
;; Para configurar o PostgreSQL (criar tabelas, etc):
;; (docai.pg/setup-pg-rag!)
