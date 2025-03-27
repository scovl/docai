(ns docai.embedding
  (:require [clojure.string :as str]))

;; Implementação de embeddings usando TF-IDF simples
;; Não depende de modelos externos, ao contrário do Ollama que usa o deepseek-r1 para o LLM

(defn extract-keywords
  "Extrai palavras-chave de um texto para indexação"
  [text]
  (filter #(> (count %) 2) (str/split (str/lower-case text) #"\s+")))

(defn tokenize
  "Divide o texto em tokens, removendo palavras com menos de 3 caracteres"
  [text]
  (when (string? text)
    (filter #(> (count %) 2) (str/split (str/lower-case text) #"\s+"))))

(defn term-freq
  "Calcula a frequência dos termos em um documento"
  [tokens]
  (frequencies tokens))

(defn doc-freq
  "Calcula a frequência dos documentos para cada termo no corpus"
  [docs]
  (let [string-docs (filter string? docs)
        _ (println (str "Processando " (count string-docs) " documentos válidos de " (count docs) " total"))
        doc-tokens (map tokenize string-docs)
        all-tokens (distinct (flatten doc-tokens))
        doc-count (count string-docs)]
    (if (zero? doc-count)
      {}
      (reduce (fn [acc term]
                (assoc acc term
                       (count (filter #(some #{term} %) doc-tokens))))
              {}
              all-tokens))))

(defn tf-idf
  "Calcula TF-IDF para um documento com base na frequência dos documentos"
  [doc doc-freq]
  (if (empty? doc-freq)
    {}
    (let [tokens (tokenize doc)
          tf (term-freq tokens)
          n-docs (count (keys doc-freq))]
      (reduce (fn [acc term]
                (let [tf-val (get tf term)
                      idf-val (Math/log (/ n-docs (get doc-freq term 1)))]
                  (assoc acc term (* tf-val idf-val))))
              {}
              (keys tf)))))

(defn vectorize
  "Converte um documento em um vetor TF-IDF com base na frequência dos documentos"
  [doc doc-freq]
  (let [tf-idf-scores (tf-idf doc doc-freq)]
    (if (empty? doc-freq)
      []
      (mapv #(get tf-idf-scores % 0.0) (keys doc-freq)))))

(defn create-embeddings
  "Gera embeddings para uma lista de textos usando TF-IDF"
  [texts]
  (try
    (let [doc-freq (doc-freq texts)]
      (mapv #(vectorize % doc-freq) texts))
    (catch Exception e
      (println "Erro ao criar embeddings: " (.getMessage e))
      (vec (repeat (count texts) [])))))

(defn cosine-similarity
  "Calcula a similaridade do cosseno entre dois vetores"
  [v1 v2]
  (if (or (empty? v1) (empty? v2))
    0.0
    (let [dot-product (reduce + (map * v1 v2))
          norm1 (Math/sqrt (reduce + (map #(* % %) v1)))
          norm2 (Math/sqrt (reduce + (map #(* % %) v2)))]
      (if (or (zero? norm1) (zero? norm2))
        0.0
        (/ dot-product (* norm1 norm2))))))

(defn similarity-search
  "Encontra os N índices de documentos mais similares à consulta"
  [query-embedding doc-embeddings n]
  (if (or (empty? query-embedding) (empty? doc-embeddings))
    (take (min n (count doc-embeddings)) (range))
    (->> (map-indexed (fn [idx doc-emb]
                        [(cosine-similarity query-embedding doc-emb) idx])
                      doc-embeddings)
         (sort-by first >)
         (take n)
         (map second))))