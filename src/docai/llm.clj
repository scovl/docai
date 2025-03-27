(ns docai.llm
  (:require [clojure.data.json :as json]
            [org.httpkit.client :as http]))

;; Use URL local por padrão, mas permita substituição por URL do container
(def ^:private ollama-base-url (atom "http://localhost:11434"))
(def ^:private model-name "deepseek-r1")
(def ^:private max-context-length 4000)

(defn set-ollama-docker-mode!
  "Configura o uso do Ollama em modo Docker/Podman"
  []
  (reset! ollama-base-url "http://ollama:11434")
  (println "✅ Configurado para usar Ollama dentro do contêiner Docker/Podman"))

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
        
        ;; Tentar primeiro com a URL primária
        primary-result (try-single-url primary-url options)]
    
    (if (:success primary-result)
      (:result primary-result)
      (do
        (println "⚠️ Erro na chamada primária:" (:error primary-result))
        (println "🔄 Tentando URLs alternativas...")
        
        ;; Lista ampliada de possíveis URLs para Ollama
        (let [alternative-hosts ["http://pgai-ollama-1:11434"   ;; Nome do contêiner no compose
                                 "http://ollama:11434"          ;; Nome curto do serviço
                                 "http://172.18.0.2:11434"      ;; Possível IP interno
                                 "http://host.docker.internal:11434"  ;; Mapeamento especial para Docker Desktop
                                 "http://localhost:11434"]      ;; Localhost
              results (atom [])]
          
          ;; Tenta cada URL até uma ter sucesso
          (doseq [host alternative-hosts]
            (when (empty? @results)
              (let [alt-url (str host "/api/generate")
                    _ (println "🔄 Tentando conectar ao Ollama em" alt-url)
                    result (try-single-url alt-url options)]
                (if (:success result)
                  (do
                    (println "✅ Conexão bem-sucedida com" alt-url)
                    (swap! results conj (:result result)))
                  (println "⚠️ Erro ao chamar a API do Ollama:" (:error result))))))
          
          (if (seq @results)
            (first @results)
            (do
              (println "❌ Todas as tentativas de conexão com Ollama falharam.")
              (str "Não foi possível conectar ao Ollama usando nenhum dos endpoints disponíveis. "
                   "Verifique se o serviço Ollama está em execução e acessível. "
                   "\n\nErro encontrado: O modelo foi encontrado mas não foi possível conectar ao serviço Ollama "
                   "para processar sua consulta."))))))))

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