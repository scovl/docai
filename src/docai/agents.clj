(ns docai.agents
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [docai.pg :as pg]
            [docai.llm :as llm]
            [docai.metrics :as metrics]
            [docai.advanced-rag :as adv-rag]
            [clojure.data.json :as json]))

;; Cache para etapas intermediárias
(def agent-cache (atom {}))

;; Tipos de agentes especializados
(def agent-types
  {:search "especializado em busca de informações em documentos. Seu papel é recuperar informações relevantes da base de conhecimento."
   :reasoning "especializado em raciocínio lógico. Seu papel é analisar informações e realizar inferências lógicas com base no contexto."
   :calculation "especializado em cálculos e análise numérica. Seu papel é processar dados numéricos e realizar operações matemáticas."
   :default "assistente especializado em documentação técnica. Seu papel é responder a consultas com base no conhecimento disponível."})

;; Funções de processamento de consulta

(defn analyze-query
  "Analisa uma consulta e identifica a intenção principal e sub-questões"
  [query]
  (let [prompt (str "Analise a seguinte consulta e identifique: 
                     1. A intenção principal (escolha uma: search, reasoning, calculation ou default)
                     2. Entre 1-3 sub-questões que precisam ser respondidas para atender à consulta principal.
                     Responda em formato JSON usando a seguinte estrutura:
                     {
                       \"intent\": \"uma das intenções listadas\",
                       \"sub_questions\": [\"sub-questão 1\", \"sub-questão 2\", ...]
                     }
                     
                     Consulta: " query)
        response (llm/call-ollama-api prompt)]
    (try
      ;; Tenta extrair JSON da resposta
      (let [json-str (re-find #"\{[\s\S]*\}" response)
            parsed (if json-str
                     (json/read-str json-str :key-fn keyword)
                     {:intent "default" :sub_questions [query]})]
        ;; Normalizar intent para keyword
        (update parsed :intent #(keyword (str/lower-case %))))
      (catch Exception e
        (println "Erro ao analisar consulta:" (.getMessage e))
        {:intent :default :sub_questions [query]}))))

(defn personal-query?
  "Verifica se a consulta contém informações pessoais que não devem ser cacheadas"
  [query]
  (let [personal-patterns ["meu" "minha" "meus" "minhas" "eu" "nome" "email" "senha" "conta"]]
    (some #(str/includes? (str/lower-case query) %) personal-patterns)))

;; Mapear intenção para tipo de agente
(defn get-agent-type
  "Determina o tipo de agente mais adequado para uma intenção"
  [intent]
  (case intent
    :search :search
    :find :search
    :retrieve :search
    :lookup :search
    :analyze :reasoning
    :explain :reasoning
    :compare :reasoning
    :reason :reasoning
    :calculate :calculation
    :compute :calculation
    :convert :calculation
    :default))

;; Funções de execução de agentes

(defn- sanitize-text
  "Remove qualquer conteúdo potencialmente problemático do texto"
  [text]
  (when text
    (-> text
        (str/replace #"```[a-zA-Z]*\n" "")  ;; Remove marcações de código
        (str/replace #"```" "")             ;; Remove backticks
        (str/replace #"<[^>]+>" "")         ;; Remove tags HTML
        (str/trim))))

;; Função de verificação de resposta disponível para uso interno
(defn verify-response
  "Usa um agente crítico para verificar e melhorar uma resposta"
  [query context response]
  (let [prompt (str "Avalie criticamente a seguinte resposta para a consulta do usuário. 
                    Verifique se a resposta é:\n"
                    "1. Fiel ao contexto fornecido\n"
                    "2. Completa (responde todos os aspectos da pergunta)\n"
                    "3. Precisa (não contém informações incorretas)\n\n"
                    "Consulta: " query "\n\n"
                    "Contexto: " (if (> (count context) 300)
                                   (str (subs context 0 300) "...") context) "\n\n"
                    "Resposta: " response "\n\n"
                    "Se a resposta for adequada, apenas responda 'A resposta está correta'. "
                    "Caso contrário, forneça uma versão melhorada.")

        ;; Chamar o modelo diretamente com o prompt
        verification (llm/call-ollama-api prompt)]

    ;; Se a verificação indicar que a resposta está correta, retornar a original
    ;; Caso contrário, retornar a versão melhorada
    (if (str/includes? verification "A resposta está correta")
      response
      (let [improved-version (str/replace verification
                                          #"(?i).*?\b(a resposta melhorada seria:|versão melhorada:|resposta corrigida:|sugestão de resposta:|aqui está uma versão melhorada:)\s*"
                                          "")]
        improved-version))))

(defn- execute-subtask
  "Executa uma subtarefa específica usando o agente apropriado"
  [subtask agent-type previous-results]
  (let [;; Personalizar o prompt com base no tipo de agente
        agent-prompt (case agent-type
                       :search (str "Procure informações específicas sobre: " subtask)
                       :reasoning (str "Analise e explique: " subtask)
                       :calculation (str "Calcule ou analise numericamente: " subtask)
                       (str "Responda a seguinte questão: " subtask))

        ;; Adicionar contexto dos resultados anteriores, se houver
        context-str (when (seq previous-results)
                      (str "Com base nas seguintes informações obtidas até agora:\n\n"
                           (str/join "\n\n" previous-results)
                           "\n\n"))

        ;; Processar contexto e subtask para segurança
        safe-context (sanitize-text (or context-str ""))
        safe-subtask (sanitize-text subtask)

        ;; Criar prompt específico para o agente 
        agent-description (get agent-types agent-type)

        ;; Combinar tudo em um prompt final
        full-prompt (llm/format-prompt safe-context
                                       (str "Você é um agente " agent-description
                                            " Você deve focar apenas nesta tarefa específica. "
                                            "Seja conciso e forneça apenas informações relevantes para a tarefa.\n\n"
                                            agent-prompt "\n\n" safe-subtask))

        ;; Chamar o LLM para obter resposta
        start-time (System/currentTimeMillis)
        response (llm/call-ollama-api full-prompt)
        duration (- (System/currentTimeMillis) start-time)

        ;; Garantir que a resposta seja segura
        safe-response (sanitize-text response)]

    ;; Retornar o resultado formatado
    {:response safe-response
     :duration duration
     :agent agent-type
     :subtask safe-subtask}))

(defn execute-agent-workflow
  "Executa o workflow completo de agentes para uma consulta complexa"
  [query]
  (let [;; Verificar cache primeiro
        cached (@agent-cache query)]
    (if cached
      (do
        (println "Usando resposta em cache para consulta complexa")
        cached)
      (let [start-time (System/currentTimeMillis)

            ;; Analisar a consulta para determinar intenção e sub-questões
            analysis (analyze-query query)
            primary-intent (get-agent-type (:intent analysis))
            subtasks (or (:sub_questions analysis) [query])

            ;; Resultados parciais
            results (atom [])

            ;; Executar cada subtarefa em sequência
            _ (doseq [subtask subtasks]
                (let [agent-result (execute-subtask
                                    subtask
                                    primary-intent
                                    @results)]
                  (swap! results conj (:response agent-result))))

            ;; Gerar resposta final sintetizada
            synthesis-prompt (str "Com base nas seguintes informações:\n\n"
                                  (str/join "\n\n" @results)
                                  "\n\nResponda à pergunta original de forma completa e coerente: " query)

            initial-response (llm/call-ollama-api synthesis-prompt)

            ;; Obter contexto combinado para verificação
            combined-context (str/join "\n\n" @results)

            ;; Verificar a qualidade da resposta
            final-response (verify-response query combined-context initial-response)

            duration (- (System/currentTimeMillis) start-time)]

        ;; Registrar no cache
        (swap! agent-cache assoc query
               (if (personal-query? query)
                 final-response
                 final-response))

        ;; Registrar métricas usando a função de log do pacote metrics
        (metrics/log-rag-interaction query [] final-response duration)

        ;; Registrar informações adicionais sobre o processo de agentes
        (when (pg/check-postgres-connection)
          (try
            (let [conn (jdbc/get-connection pg/db-spec)]
              (jdbc/execute! conn
                             ["INSERT INTO agent_executions 
                              (query, primary_intent, agent_type, subtasks, duration_ms) 
                              VALUES (?, ?, ?, ?, ?)"
                              query
                              (name (:intent analysis))
                              (name primary-intent)
                              (count subtasks)
                              duration])
              (.close conn))
            (catch Exception e
              (println "Erro ao registrar execução de agente:" (.getMessage e)))))

        final-response))))

(defn needs-agent-workflow?
  "Determina se uma consulta é complexa o suficiente para justificar o uso do workflow de agentes"
  [query]
  (let [complexity-indicators ["compare" "diferença" "explique" "analise" "relação" "calcule"
                               "passo a passo" "como funciona" "por que" "pros e contras"
                               "vantagens" "desvantagens" "múltiplos" "diversos"]]
    (or (> (count (str/split query #"\s+")) 15)  ;; Consultas longas
        (some #(str/includes? (str/lower-case query) %) complexity-indicators))))

;; Criação de tabelas e inicialização

(defn setup-agent-tables!
  "Configura tabelas necessárias para o sistema de agentes"
  []
  (if (pg/check-postgres-connection)
    (try
      (println "Configurando tabelas para o sistema de agentes...")
      (let [conn (jdbc/get-connection pg/db-spec)]

        ;; Tabela para armazenar informações sobre execuções de agentes
        (jdbc/execute! conn
                       ["CREATE TABLE IF NOT EXISTS agent_executions (
                        id SERIAL PRIMARY KEY,
                        query TEXT NOT NULL,
                        primary_intent TEXT NOT NULL,
                        agent_type TEXT NOT NULL,
                        subtasks INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                      )"])

        (println "✅ Tabelas para o sistema de agentes configuradas com sucesso!")
        true)
      (catch Exception e
        (println "❌ Erro ao configurar tabelas para agentes:" (.getMessage e))
        false))
    (do
      (println "❌ Sem conexão com PostgreSQL para configurar tabelas de agentes")
      false)))

;; Função principal para uso externo
(defn process-with-agents
  "Função principal para processar consultas complexas com agentes"
  [query]
  (if (needs-agent-workflow? query)
    (execute-agent-workflow query)
    (adv-rag/advanced-rag-query query)))