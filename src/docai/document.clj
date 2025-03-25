(ns docai.document
  (:require [markdown-to-hiccup.core :as md]
            [hickory.core :as html]
            [clojure.string :as str]))

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