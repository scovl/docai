(ns docai.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [docai.document :as doc]
            [docai.embedding :as emb]
            [docai.llm :as llm]
            [docai.pg :as pg]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:gen-class))

(def ^:private docs-path "resources/docs")
(def ^:private use-postgres (atom false))

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

(defn import-docs-to-postgres
  "Importa documentos para o PostgreSQL"
  []
  (println "Importando documentos para o PostgreSQL...")
  (let [doc-files (load-documentation)]
    (if (empty? doc-files)
      (println "Aviso: Nenhum arquivo de documentação encontrado em" docs-path)
      (doseq [file doc-files]
        (pg/import-markdown-file! file "documentacao")))))

(defn -main
  "Função principal que inicializa a aplicação DocAI e processa consultas do usuário"
  [& args]
  (println "Inicializando DocAI...")

  ;; Verificar argumentos de linha de comando
  (when (some #{"--postgres"} args)
    (reset! use-postgres true)
    (println "Modo PostgreSQL ativado!"))
  
  ;; Verificar se o Ollama está acessível
  (println "ℹ️ Para usar o Ollama, certifique-se de que ele está em execução com o comando: ollama serve")
  (println "ℹ️ Usando o modelo deepseek-r1. Se você ainda não o baixou, execute: ollama pull deepseek-r1")
  
  (if @use-postgres
    ;; Setup PostgreSQL RAG
    (do
      (println "Configurando ambiente PostgreSQL para RAG...")
      ;; Configurar Ollama para usar o endereço do container
      (llm/set-ollama-docker-mode!)
      (pg/setup-pg-rag!)
      (import-docs-to-postgres)
      (println "PostgreSQL RAG pronto! Faça sua pergunta:")
      (try
        (loop []
          (when-let [input (read-line)]
            (if (= input "sair")
              (println "Obrigado por usar o DocAI. Até a próxima!")
              (do
                (println "Processando...")
                (println (query-pg-rag input))
                (println "\nPróxima pergunta (ou 'sair' para terminar):")
                (recur)))))
        (catch Exception e
          (println "Erro: " (.getMessage e))
          (println "Detalhes: " (ex-data e))
          (println "Obrigado por usar o DocAI. Até a próxima!"))))
    
    ;; Setup tradicional em memória
    (let [kb (setup-knowledge-base)]
      (println "Base de conhecimento pronta! Faça sua pergunta:")
      (try
        (loop []
          (when-let [input (read-line)]
            (if (= input "sair")
              (println "Obrigado por usar o DocAI. Até a próxima!")
              (do
                (println "Processando...")
                (println (query-rag kb input))
                (println "\nPróxima pergunta (ou 'sair' para terminar):")
                (recur)))))
        (catch Exception e
          (println "Erro: " (.getMessage e))
          (println "Detalhes: " (ex-data e))
          (println "Obrigado por usar o DocAI. Até a próxima!"))))))

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
