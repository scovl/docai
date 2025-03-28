(ns docai.llm
  (:require [clojure.data.json :as json]
            [org.httpkit.client :as http]))

;; Use URL local por padrão, mantendo apenas localhost pois é o único que funciona
(def ^:private ollama-base-url (atom "http://localhost:11434"))
(def ^:private model-name "deepseek-r1")
(def ^:private max-context-length 4000)

(defn set-ollama-docker-mode!
  "Configura o uso do Ollama em modo Docker/Podman"
  []
  (reset! ollama-base-url "http://localhost:11434")
  (println "✅ Configurado para usar Ollama em localhost:11434"))

(defn- api-error-message
  "Formata mensagem de erro da API"
  [response]
  (str "Erro ao chamar a API do Ollama: "
       (:status response) " - "
       (:body response)))

(defn- truncate-context
  "Limita o contexto a um tamanho máximo para evitar problemas com o modelo"
  [context max-length]
  (if (<= (count context) max-length)
    context
    (let [half-length (int (/ max-length 2))
          start (subs context 0 half-length)
          end (subs context (- (count context) half-length))]
      (str start "\n...\n[Conteúdo truncado para melhor processamento]\n...\n" end))))

(defn- try-single-url
  "Tenta fazer uma única chamada para uma URL específica"
  [url options]
  (try
    (let [response @(http/post url options)]
      (if (= (:status response) 200)
        {:success true
         :result (-> response
                     :body
                     (json/read-str :key-fn keyword)
                     :response)}
        {:success false
         :error (api-error-message response)}))
    (catch Exception e
      {:success false
       :error (str "Falha ao conectar em " url ": " (.getMessage e))})))

(defn call-ollama-api
  "Chama a API do Ollama para gerar uma resposta baseada no prompt fornecido"
  [prompt]
  (let [primary-url (str @ollama-base-url "/api/generate")
        _ (println "DEBUG - Usando URL do Ollama:" primary-url)
        request-body {:model model-name
                      :prompt prompt
                      :stream false}
        options {:headers {"Content-Type" "application/json"}
                 :body (json/write-str request-body)
                 :timeout 120000}  ;; Aumentar o timeout para 2 minutos

        ;; Tentar com a URL principal
        result (try-single-url primary-url options)]

    (if (:success result)
      (:result result)
      (do
        (println "❌ Erro ao conectar ao Ollama:" (:error result))
        (str "Não foi possível conectar ao Ollama usando " primary-url ". "
             "Verifique se o serviço Ollama está em execução e acessível. "
             "\n\nErro encontrado: " (:error result))))))

;; Funções de utilidade para uso futuro:
;;
;; extract-code-blocks: Extrai blocos de código do texto usando regex
;; exemplo de uso:
;;   (extract-code-blocks "```clojure\n(+ 1 2)\n```") => ["(+ 1 2)"]
;;
;; extract-summary: Cria um resumo de texto com tamanho máximo especificado
;; exemplo de uso:
;;   (extract-summary "# Título\nConteúdo longo..." 50) => "Conteúdo longo..."

(defn format-prompt
  "Formata o prompt para o LLM com o contexto e a consulta do usuário"
  [context query]
  (let [truncated-context (truncate-context context max-context-length)]
    (str "Você é um assistente especializado em documentação técnica. Com base no seguinte contexto da documentação:\n\n"
         truncated-context
         "\n\nPergunta: " query
         "\n\nForneça uma resposta técnica precisa e, se possível, inclua exemplos de código. "
         "Se a documentação não contiver informações relevantes para a pergunta, "
         "indique isso claramente e forneça uma resposta geral com base em seu conhecimento.")))

(defn generate-response
  "Gera resposta usando o LLM com base no contexto e na consulta do usuário"
  [query context]
  (try
    (let [prompt (format-prompt context query)]
      (println "DEBUG - Enviando prompt para o Ollama usando o modelo" model-name)
      (println "DEBUG - Tamanho do prompt após truncamento:" (count prompt) "caracteres")
      (call-ollama-api prompt))
    (catch Exception e
      (str "Erro ao gerar resposta: " (.getMessage e)
           "\n\nPor favor, verifique se o Ollama está em execução no endereço "
           @ollama-base-url
           "\n\nVocê pode iniciar o Ollama com o comando: ollama serve"))))