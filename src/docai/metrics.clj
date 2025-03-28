(ns docai.metrics
  (:require [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [docai.pg :as pg]
            [docai.llm :as llm]
            [clojure.string :as str]))

;; Tabela para logs de interações RAG
(def rag-logs-table "CREATE TABLE IF NOT EXISTS rag_logs (
                      id SERIAL PRIMARY KEY,
                      query TEXT NOT NULL,
                      retrieved_docs TEXT,
                      response TEXT,
                      latency_ms INTEGER,
                      timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )")

;; Tabela para feedback do usuário
(def user-feedback-table "CREATE TABLE IF NOT EXISTS user_feedback (
                           id SERIAL PRIMARY KEY,
                           query_id INTEGER REFERENCES rag_logs(id) ON DELETE CASCADE,
                           response_id INTEGER REFERENCES rag_logs(id),
                           feedback_type TEXT CHECK (feedback_type IN ('positive', 'negative', 'neutral')),
                           feedback_text TEXT,
                           timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                         )")

(defn setup-metrics-tables!
  "Configura tabelas para métricas e monitoramento"
  []
  (if (pg/check-postgres-connection)
    (let [conn (jdbc/get-connection pg/db-spec)]
      (try
        (jdbc/execute! conn [rag-logs-table])
        (jdbc/execute! conn [user-feedback-table])
        (println "✅ Tabelas de métricas configuradas com sucesso")
        true
        (catch Exception e
          (println "❌ Erro ao configurar tabelas de métricas:" (.getMessage e))
          false)
        (finally
          (.close conn))))
    false))

(defn log-rag-interaction
  "Registra uma interação RAG para análise posterior"
  [query retrieved-docs response latency]
  (if (pg/check-postgres-connection)
    (let [conn (jdbc/get-connection pg/db-spec)]
      (try
        (jdbc/execute! conn
                       ["INSERT INTO rag_logs 
                        (query, retrieved_docs, response, latency_ms)
                        VALUES (?, ?, ?, ?)"
                        query
                        (json/write-str retrieved-docs)
                        response
                        latency])
        (println "✅ Interação RAG registrada com sucesso")
        true
        (catch Exception e
          (println "⚠️ Erro ao registrar interação RAG:" (.getMessage e))
          false)
        (finally
          (.close conn))))
    ;; Se não conseguir conectar ao banco, apenas logar no console
    (do
      (println "ℹ️ Métricas (modo offline):")
      (println "  Query: " query)
      (println "  Docs recuperados: " (count retrieved-docs))
      (println "  Latência: " latency "ms")
      false)))

(defn process-user-feedback
  "Processa feedback explícito do usuário"
  [query-id feedback-type feedback-text]
  (if (pg/check-postgres-connection)
    (let [conn (jdbc/get-connection pg/db-spec)]
      (try
        ;; Registrar feedback no banco de dados
        (jdbc/execute! conn
                       ["INSERT INTO user_feedback 
                        (query_id, feedback_type, feedback_text) 
                        VALUES (?, ?, ?)"
                        query-id feedback-type feedback-text])

        ;; Para feedback negativo, adicionar à fila de revisão
        (when (= feedback-type "negative")
          (println "⚠️ Feedback negativo registrado para análise"))

        (println "✅ Feedback processado com sucesso")
        true
        (catch Exception e
          (println "❌ Erro ao processar feedback:" (.getMessage e))
          false)
        (finally
          (.close conn))))
    false))

(defn calculate-rag-metrics
  "Calcula métricas de desempenho para um período"
  [start-date end-date]
  (when (pg/check-postgres-connection)
    (let [conn (jdbc/get-connection pg/db-spec)]
      (try
        (let [logs (jdbc/execute! conn
                                 ["SELECT * FROM rag_logs 
                                  WHERE timestamp BETWEEN ? AND ?"
                                  start-date end-date])
              count-logs (count logs)
              avg-latency (if (pos? count-logs)
                            (/ (reduce + (map :latency_ms logs)) count-logs)
                            0)
              sorted-latencies (sort (map :latency_ms logs))
              p95-latency (if (pos? count-logs)
                            (nth sorted-latencies (int (* 0.95 count-logs)) 0)
                            0)
              results {:total_queries count-logs
                       :avg_latency avg-latency
                       :p95_latency p95-latency}]
          (println "📊 Métricas RAG calculadas:")
          (println "  Total de consultas: " count-logs)
          (println "  Latência média: " avg-latency "ms")
          (println "  Latência P95: " p95-latency "ms")
          results)
        (catch Exception e
          (println "❌ Erro ao calcular métricas:" (.getMessage e))
          nil)
        (finally (.close conn))))))

;; Função auxiliar para chamar LLM avaliador
(defn call-evaluation-llm
  "Chama LLM para avaliação (usa o mesmo LLM da aplicação por simplicidade)"
  [prompt]
  (try
    (llm/call-ollama-api prompt)
    (catch Exception e
      (println "Erro ao chamar LLM avaliador:" (.getMessage e))
      "Erro na avaliação. Score padrão: 5/10.")))

(defn evaluate-response-quality
  "Avalia métricas qualitativas de uma resposta RAG usando LLM.
   Esta função implementa avaliação automatizada de qualidade para monitoramento contínuo."
  [query context response]
  (let [;; Construir prompt para avaliação de fidelidade
        prompt-faithfulness (str "Você é um avaliador especializado em sistemas RAG. "
                                 "Avalie a fidelidade da seguinte resposta ao contexto fornecido.\n\n"
                                 "Consulta: " query "\n\n"
                                 "Contexto: " (if (> (count context) 500)
                                                (str (subs context 0 500) "...")
                                                context) "\n\n"
                                 "Resposta: " response "\n\n"
                                 "A resposta contém informações que não estão no contexto? "
                                 "A resposta contradiz o contexto em algum ponto? "
                                 "Atribua uma pontuação de 1 a 10, onde 10 significa perfeita fidelidade ao contexto.")

        ;; Chamar LLM para avaliação
        faithfulness-result (call-evaluation-llm prompt-faithfulness)

        ;; Extrair score numérico (implementação simplificada)
        score-pattern #"(\d+)(?:\.\d+)?"
        matches (re-find score-pattern faithfulness-result)
        score (if matches
                (try
                  (Integer/parseInt (second matches))
                  (catch Exception _ 5))
                5)]

    {:faithfulness score
     :evaluation faithfulness-result}))

;; Adicionar uma função para coletar feedback de usuário via console
(defn collect-user-feedback
  "Coleta feedback diretamente do usuário para uma consulta específica"
  [query-id]
  (println "\n=== Feedback para a consulta ===")
  (println "Como você avalia a resposta? (positivo/negativo/neutro)")
  (print "Avaliação: ")
  (flush)
  (let [feedback-type (str/lower-case (or (read-line) "neutro"))]
    ;; Validar entrada
    (if (contains? #{"positivo" "negativo" "neutro"} feedback-type)
      (do
        (println "Comentários adicionais (opcional):")
        (print "> ")
        (flush)
        (let [feedback-text (read-line)
              ;; Padronizar tipos de feedback para o formato do banco
              db-feedback-type (case feedback-type
                                 "positivo" "positive"
                                 "negativo" "negative"
                                 "neutro" "neutral")]
          (if (process-user-feedback query-id db-feedback-type feedback-text)
            (println "✅ Feedback registrado com sucesso!")
            (println "❌ Não foi possível registrar o feedback."))))
      (println "❌ Tipo de feedback inválido. Use 'positivo', 'negativo' ou 'neutro'."))))