(ns docai.core
  (:require [docai.document :as doc]
            [docai.embedding :as emb]
            [docai.llm :as llm]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:gen-class))

(def ^:private docs-path "resources/docs")

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

(defn -main
  "Função principal que inicializa a aplicação DocAI e processa consultas do usuário"
  [& _]
  (println "Inicializando DocAI...")
  
  ;; Verificar se o Ollama está acessível
  (println "ℹ️ Para usar o Ollama, certifique-se de que ele está em execução com o comando: ollama serve")
  (println "ℹ️ Usando o modelo deepseek-r1. Se você ainda não o baixou, execute: ollama pull deepseek-r1")
  
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
        (println "Obrigado por usar o DocAI. Até a próxima!")))))
