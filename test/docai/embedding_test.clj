(ns docai.embedding-test
  (:require [clojure.test :refer [deftest testing is]]
            [docai.embedding :as emb]))

(deftest test-tokenize
  (testing "Tokenização de texto"
    (is (= '("texto" "exemplo" "para" "teste") (emb/tokenize "texto de exemplo para teste")))
    (is (empty? (emb/tokenize "a b c")))  ; palavras com menos de 3 caracteres são filtradas
    (is (= '("texto") (emb/tokenize "TEXTO")))  ; verifica conversão para minúsculas
    (is (nil? (emb/tokenize nil)))  ; deve lidar com nil
    (is (empty? (emb/tokenize 123)))))  ; deve lidar com não-strings

(deftest test-term-freq
  (testing "Frequência de termos"
    (is (= {"texto" 1, "exemplo" 1} (emb/term-freq ["texto" "exemplo"])))
    (is (= {"texto" 2} (emb/term-freq ["texto" "texto"])))
    (is (empty? (emb/term-freq [])))))

(deftest test-doc-freq
  (testing "Frequência de documentos"
    (let [docs ["primeiro texto exemplo" "segundo texto" "terceiro exemplo"]
          result (emb/doc-freq docs)]
      (is (= 2 (get result "texto")))
      (is (= 2 (get result "exemplo")))
      (is (= 1 (get result "primeiro")))
      (is (= 1 (get result "segundo")))
      (is (= 1 (get result "terceiro"))))
    (is (empty? (emb/doc-freq [])))
    (is (empty? (emb/doc-freq nil)))
    (is (empty? (emb/doc-freq [nil 123])))))

(deftest test-tf-idf
  (testing "Cálculo de TF-IDF"
    (let [docs ["primeiro texto exemplo" "segundo texto" "terceiro exemplo"]
          doc-freq (emb/doc-freq docs)
          result (emb/tf-idf "texto exemplo" doc-freq)]
      (is (map? result))
      (is (contains? result "texto"))
      (is (contains? result "exemplo"))
      (is (> (get result "texto") 0))
      (is (> (get result "exemplo") 0)))
    (is (empty? (emb/tf-idf "texto" {})))
    (is (empty? (emb/tf-idf "" {:a 1})))))

(deftest test-vectorize
  (testing "Vetorização de documento"
    (let [docs ["primeiro texto exemplo" "segundo texto" "terceiro exemplo"]
          doc-freq (emb/doc-freq docs)
          result (emb/vectorize "texto exemplo" doc-freq)]
      (is (vector? result))
      (is (= (count result) (count (keys doc-freq))))
      (is (every? number? result)))
    (is (empty? (emb/vectorize "texto" {})))
    (is (vector? (emb/vectorize "" {:a 1})))))

(deftest test-create-embeddings
  (testing "Criação de embeddings para um conjunto de documentos"
    (let [docs ["primeiro texto exemplo" "segundo texto" "terceiro exemplo"]
          result (emb/create-embeddings docs)]
      (is (= (count result) (count docs)))
      (is (every? vector? result))
      (is (apply = (map count result)))  ; todos os vetores devem ter o mesmo tamanho
      (is (every? number? (flatten result)))))

  (testing "Criação de embeddings com documentos inválidos"
    (let [docs [nil "texto" 123]
          result (emb/create-embeddings docs)]
      (is (= (count result) 3))  ; mesmo com entrada inválida, retorna a mesma quantidade de vetores
      (is (every? vector? result)))))

(deftest test-cosine-similarity
  (testing "Similaridade do cosseno entre vetores"
    (is (= 1.0 (emb/cosine-similarity [1 2 3] [1 2 3])))  ; vetores idênticos
    (is (= 0.0 (emb/cosine-similarity [1 0 0] [0 1 0])))  ; vetores ortogonais
    (is (< 0.9 (emb/cosine-similarity [1 1 1] [1 1 0.9])))  ; vetores similares
    (is (= 0.0 (emb/cosine-similarity [] [1 2])))  ; vetor vazio
    (is (= 0.0 (emb/cosine-similarity [1 2] [])))  ; outro vetor vazio
    (is (= 0.0 (emb/cosine-similarity [0 0 0] [1 2 3])))))  ; vetor de zeros

(deftest test-similarity-search
  (testing "Busca de similaridade entre documentos"
    (let [embeddings [[1 1 1] [1 0 0] [0 1 0] [0 0 1]]
          query [1 1 0]
          result (emb/similarity-search query embeddings 2)]
      (is (= 2 (count result)))
      (is (= 0 (first result)))  ; o primeiro resultado deve ser o índice 0
      (is (= 1 (second result))))  ; o segundo resultado deve ser o índice 1

    (testing "Busca com embeddings vazios"
      (is (= [0] (emb/similarity-search [] [[]] 1)))
      (is (= [] (emb/similarity-search [1] [] 1)))
      (is (= [0 1] (emb/similarity-search [] [[] []] 3))))))