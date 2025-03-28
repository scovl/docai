(ns docai.llm-test
  (:require [clojure.test :refer [deftest testing is]]
            [docai.llm :as llm]))

(deftest test-format-prompt
  (testing "Formatação do prompt para o LLM"
    (let [context "Este é um contexto de exemplo"
          query "Como funciona isso?"
          result (llm/format-prompt context query)]
      (is (string? result))
      (is (.contains result context))
      (is (.contains result query))
      (is (.contains result "assistente especializado em documentação técnica"))
      (is (.contains result "Forneça uma resposta técnica precisa")))))

(deftest test-format-prompt-with-empty-inputs
  (testing "Formatação do prompt com entradas vazias"
    (let [result (llm/format-prompt "" "")]
      (is (string? result))
      (is (.contains result "Pergunta: "))
      (is (.contains result "Forneça uma resposta técnica precisa")))))

(deftest test-format-prompt-with-nil-inputs
  (testing "Formatação do prompt com entradas nil"
    (let [result (llm/format-prompt nil nil)]
      (is (string? result))
      (is (.contains result "Pergunta: "))
      (is (.contains result "Forneça uma resposta técnica precisa")))))

(deftest test-generate-response
  (testing "Geração de resposta com contexto e consulta válidos"
    (let [context "Este é um contexto de exemplo"
          query "Como funciona isso?"
          result (llm/generate-response query context)]
      (is (string? result))
      (is (seq result)))))

(deftest test-generate-response-with-empty-inputs
  (testing "Geração de resposta com entradas vazias"
    (let [result (llm/generate-response "" "")]
      (is (string? result))
      (is (seq result)))))

(deftest test-generate-response-with-nil-inputs
  (testing "Geração de resposta com entradas nil"
    (let [result (llm/generate-response nil nil)]
      (is (string? result))
      (is (seq result)))))

(deftest test-generate-response-with-long-context
  (testing "Geração de resposta com contexto longo"
    (let [long-context (apply str (repeat 1000 "texto de exemplo "))
          query "Resuma o contexto"
          result (llm/generate-response query long-context)]
      (is (string? result))
      (is (seq result)))))

(deftest test-generate-response-with-special-characters
  (testing "Geração de resposta com caracteres especiais"
    (let [context "Contexto com caracteres especiais: !@#$%^&*()"
          query "Pergunta com caracteres especiais: !@#$%^&*()"
          result (llm/generate-response query context)]
      (is (string? result))
      (is (seq result)))))