(ns docai.document-test
  (:require [clojure.test :refer [deftest testing is]]
            [docai.document :as doc]))

(def sample-markdown
  "# Título Principal\n\n## Subtítulo\n\nEste é um texto de exemplo em markdown.\n\n```clojure\n(+ 1 2)\n```\n\nMais texto aqui.")

(def sample-html
  "<html><body><h1>Título Principal</h1><h2>Subtítulo</h2><p>Este é um texto de exemplo em HTML.</p><pre><code>(+ 1 2)</code></pre><p>Mais texto aqui.</p></body></html>")

(deftest test-extract-text-from-markdown
  (testing "Extração de texto de Markdown"
    (let [result (doc/extract-text-from-markdown sample-markdown)]
      (is (seq? result))
      (is (every? string? result))
      (is (.contains (apply str result) "Título Principal"))
      (is (.contains (apply str result) "Subtítulo"))
      (is (.contains (apply str result) "texto de exemplo")))))

(deftest test-extract-text-from-markdown-with-empty-input
  (testing "Extração de texto de Markdown vazio"
    (let [result (doc/extract-text-from-markdown "")]
      (is (empty? result)))))

(deftest test-extract-text-from-markdown-with-nil-input
  (testing "Extração de texto de Markdown nil"
    (let [result (doc/extract-text-from-markdown nil)]
      (is (vector? result))
      (is (= 1 (count result)))
      (is (nil? (first result))))))

(deftest test-extract-text-from-html
  (testing "Extração de texto de HTML"
    (let [result (doc/extract-text-from-html sample-html)]
      (is (seq? result))
      (is (every? string? result))
      (is (.contains (apply str result) "Título Principal"))
      (is (.contains (apply str result) "Subtítulo"))
      (is (.contains (apply str result) "texto de exemplo")))))

(deftest test-extract-text-from-html-with-empty-input
  (testing "Extração de texto de HTML vazio"
    (let [result (doc/extract-text-from-html "")]
      (is (empty? result)))))

(deftest test-extract-text-from-html-with-nil-input
  (testing "Extração de texto de HTML nil"
    (let [result (doc/extract-text-from-html nil)]
      (is (vector? result))
      (is (= 1 (count result)))
      (is (nil? (first result))))))

(deftest test-extract-text
  (testing "Extração de texto de arquivo"
    (let [temp-file (java.io.File/createTempFile "test" ".md")]
      (try
        (spit temp-file sample-markdown)
        (let [result (doc/extract-text (.getPath temp-file))]
          (is (seq? result))
          (is (every? seq? result))
          (is (every? #(every? string? %) result))
          (is (.contains (apply str (flatten result)) "Título Principal"))
          (is (.contains (apply str (flatten result)) "Subtítulo")))
        (finally
          (.delete temp-file))))))

(deftest test-extract-text-with-invalid-file
  (testing "Extração de texto de arquivo inválido"
    (let [result (try
                   (doc/extract-text "arquivo_que_nao_existe.md")
                   (catch Exception e
                     (.getMessage e)))]
      (is (string? result))
      (is (.contains result "O sistema não pode encontrar o arquivo especificado")))))

(deftest test-preprocess-chunks
  (testing "Pré-processamento de chunks de texto"
    (let [chunks [["texto" "de" "exemplo"] ["mais" "texto"]]
          result (doc/preprocess-chunks chunks)]
      (is (seq? result))
      (is (= 2 (count result)))
      (is (every? string? result))
      (is (= "texto de exemplo" (first result)))
      (is (= "mais texto" (second result))))))

(deftest test-preprocess-chunks-with-empty-input
  (testing "Pré-processamento de chunks vazios"
    (let [result (doc/preprocess-chunks [])]
      (is (empty? result)))))

(deftest test-preprocess-chunks-with-nil-input
  (testing "Pré-processamento de chunks nil"
    (let [result (doc/preprocess-chunks nil)]
      (is (empty? result)))))

(deftest test-preprocess-chunks-with-mixed-input
  (testing "Pré-processamento de chunks com conteúdo misto"
    (let [chunks [["texto" "de" "exemplo"] nil ["mais" "texto"]]
          result (doc/preprocess-chunks chunks)]
      (is (seq? result))
      (is (= 2 (count result)))
      (is (every? string? result))
      (is (= "texto de exemplo" (first result)))
      (is (= "mais texto" (second result))))))