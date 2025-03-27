(ns docai.document
  (:require [markdown-to-hiccup.core :as md]
            [hickory.core :as html]
            [clojure.string :as str]
            [docai.advanced-rag :as adv-rag]))

(defn extract-text-from-markdown
  "Extrai texto de conteúdo Markdown"
  [content]
  (try
    (->> content
         md/md->hiccup
         flatten
         (filter string?))
    (catch Exception e
      (println "Erro ao processar Markdown:" (.getMessage e))
      [content])))

(defn extract-text-from-html
  "Extrai texto de conteúdo HTML"
  [content]
  (try
    (->> content
         html/parse
         html/as-hiccup
         flatten
         (filter string?))
    (catch Exception e
      (println "Erro ao processar HTML:" (.getMessage e))
      [content])))

(defn extract-text
  "Extrai texto de documentação (Markdown ou HTML)"
  [doc-path]
  (println "Extraindo texto de:" doc-path)
  (let [content (slurp doc-path)
        _ (println "Tamanho do conteúdo:" (count content) "caracteres")
        _ (println "Amostra do conteúdo:" (subs content 0 (min 100 (count content))))
        text (if (.endsWith doc-path ".md")
               (extract-text-from-markdown content)
               (extract-text-from-html content))
        _ (println "Quantidade de nós de texto extraídos:" (count text))
        chunks (partition-all 512 text)]  ; 512 tokens por chunk
    (println "Quantidade de chunks gerados:" (count chunks))
    chunks))

(defn preprocess-chunks
  "Limpa e prepara os chunks de texto para processamento"
  [chunks]
  (when chunks
    (->> chunks
         (filter (complement nil?))
         (map (fn [chunk] 
                (when chunk
                  (-> chunk
                      (str/join " ")
                      (str/replace #"\s+" " ")
                      str/trim))))
         (filter (complement str/blank?)))))

;; Integração com chunking dinâmico
(defn detect-document-type
  "Detecta o tipo de documento com base no conteúdo e extensão"
  [file-path content]
  (let [extension (last (str/split file-path #"\."))
        lower-ext (when extension (str/lower-case extension))]
    (cond
      ;; Detectar código por extensão
      (contains? #{"py" "java" "js" "ts" "c" "cpp" "cs" "go" "rs" "php" "rb" "scala" "kt" "swift" "clj" "cljs" "cljc"} lower-ext)
      "code"
      
      ;; Detectar documentos legais por conteúdo
      (or (str/includes? (str/lower-case content) "contrato")
          (str/includes? (str/lower-case content) "acordo")
          (str/includes? (str/lower-case content) "legal")
          (str/includes? (str/lower-case content) "lei"))
      "legal"
      
      ;; Detectar documentos de Q&A por conteúdo
      (or (re-find #"(?i)(?:pergunta|p):.*?(?:resposta|r):.*" content)
          (re-find #"(?i)^Q:.*?A:.*" content)
          (re-find #"(?i)FAQ|perguntas\s+frequentes" content))
      "qa"
      
      ;; Default para artigos
      :else "article")))

(defn read-document
  "Lê o conteúdo de um documento"
  [file-path]
  (try
    (slurp file-path)
    (catch Exception e
      (println "Erro ao ler documento:" (.getMessage e))
      "")))

;; Declarar process-with-dynamic-chunking para evitar erros de referência forward
(declare process-with-dynamic-chunking)

;; Função para processamento em lote de documentos
(defn process-directory-with-dynamic-chunking
  "Processa recursivamente todos os documentos em um diretório usando chunking dinâmico"
  [dir-path]
  (let [dir (java.io.File. dir-path)]
    (if (.isDirectory dir)
      (let [files (.listFiles dir)
            file-count (atom 0)
            start-time (System/currentTimeMillis)]
        (doseq [file files]
          (if (.isFile file)
            (try
              (process-with-dynamic-chunking (.getPath file))
              (swap! file-count inc)
              (catch Exception e
                (println "❌ Erro ao processar arquivo" (.getName file) ":" (.getMessage e))))
            (when (.isDirectory file)
              (process-directory-with-dynamic-chunking (.getPath file)))))
        (let [end-time (System/currentTimeMillis)
              duration (/ (- end-time start-time) 1000.0)]
          (println (str "✅ Processamento concluído: " @file-count " arquivos em " duration " segundos."))))
      (println "❌ Caminho fornecido não é um diretório:" dir-path))))

(defn process-with-dynamic-chunking
  "Processa um documento com chunking dinâmico"
  [file-path]
  (let [file (java.io.File. file-path)]
    (if (.isDirectory file)
      ;; Se for um diretório, processá-lo recursivamente
      (do
        (println "Processando diretório:" file-path)
        (process-directory-with-dynamic-chunking file-path))
      ;; Se for um arquivo, processar como documento
      (let [content (read-document file-path)
            document-type (detect-document-type file-path content)
            doc-id (str (java.util.UUID/randomUUID))]
        
        (println "Processando arquivo" file-path "como documento tipo" document-type)
        
        ;; Usar a função de processamento dinâmico de docai.advanced-rag
        (try
          (let [chunks-count (adv-rag/process-document-with-dynamic-chunking! 
                              doc-id content document-type)]
            (println "✅ Documento processado com" chunks-count "chunks."))
          (catch Exception e
            (println "❌ Erro ao processar documento com chunking dinâmico:" (.getMessage e)))))))) 