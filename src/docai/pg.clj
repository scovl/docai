(ns docai.pg
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]
            [docai.llm :as llm])
  (:import [java.net Socket ConnectException]))

;; Configuração da conexão com o PostgreSQL
(def db-spec
  {:dbtype "postgresql"
   :dbname "postgres"
   :host "localhost"
   :port 5432
   :user "postgres"
   :password "postgres"})

(defn- get-container-cmd
  "Retorna 'podman' ou 'docker' dependendo do ambiente"
  []
  (let [container-env (System/getenv "CONTAINER_ENGINE")]
    (if (and container-env (= (str/lower-case container-env) "podman"))
      "podman"
      "docker")))

(defn- check-postgres-connection
  "Verifica se o PostgreSQL está acessível no host e porta especificados"
  []
  (try
    (let [socket (Socket. (:host db-spec) (:port db-spec))]
      (.close socket)
      true)
    (catch ConnectException _
      (let [container-cmd (get-container-cmd)
            cmd-compose (if (= container-cmd "podman") "podman-compose" "docker compose")
            script-cmd (if (= (System/getProperty "os.name") "Windows") "run.bat" "./run.sh")
            start-cmd (if (= container-cmd "podman") "podman-start" "docker-start")]
        (println "❌ Erro: Não foi possível conectar ao PostgreSQL em" 
                 (str (:host db-spec) ":" (:port db-spec)))
        (println "   Verifique se os containers estão em execução com '" cmd-compose " ps'")
        (println "   Você pode iniciar os containers com '" script-cmd " " start-cmd "'"))
      false)
    (catch Exception e
      (println "❌ Erro ao verificar conexão com PostgreSQL:" (.getMessage e))
      false)))

(defn init-db!
  "Inicializa o banco de dados com as extensões necessárias"
  []
  (if (check-postgres-connection)
    (let [conn (jdbc/get-connection db-spec)
          statements ["CREATE EXTENSION IF NOT EXISTS vector CASCADE"
                      "CREATE EXTENSION IF NOT EXISTS ai CASCADE"]]
      (try
        (doseq [stmt statements]
          (jdbc/execute! conn [stmt]))
        (println "✅ Extensões vector e ai habilitadas com sucesso")
        true
        (catch Exception e
          (println "❌ Erro ao habilitar extensões:" (.getMessage e))
          false)
        (finally
          (.close conn))))
    false))

(defn create-tables!
  "Cria as tabelas necessárias para armazenar documentos"
  []
  (if (check-postgres-connection)
    (let [conn (jdbc/get-connection db-spec)
          create-docs-table "CREATE TABLE IF NOT EXISTS documentos (
                              id SERIAL PRIMARY KEY,
                              titulo TEXT NOT NULL,
                              conteudo TEXT,
                              categoria TEXT,
                              data_criacao TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            )"]
      (try
        (jdbc/execute! conn [create-docs-table])
        (println "✅ Tabela de documentos criada com sucesso")
        true
        (catch Exception e
          (println "❌ Erro ao criar tabela:" (.getMessage e))
          false)
        (finally
          (.close conn))))
    false))

(defn- check-table-exists
  "Verifica se uma tabela existe no banco de dados"
  [table-name]
  (if (check-postgres-connection)
    (let [conn (jdbc/get-connection db-spec)
          check-sql (str "SELECT EXISTS (
                           SELECT FROM information_schema.tables 
                           WHERE table_schema = 'public' 
                           AND table_name = '" table-name "'
                         )")]
      (try
        (let [exists? (:exists (jdbc/execute-one! conn [check-sql] {:builder-fn rs/as-unqualified-maps}))]
          exists?)
        (catch Exception e
          (println "❌ Erro ao verificar existência da tabela:" (.getMessage e))
          false)
        (finally
          (.close conn))))
    false))

(defn create-vectorizer!
  "Configura o vectorizer para gerar embeddings automaticamente"
  []
  (if (check-postgres-connection)
    (let [conn (jdbc/get-connection db-spec)]
      (try
        ;; Primeiro verificar se a tabela de embeddings já existe
        (if (check-table-exists "documentos_embeddings")
          (println "✅ Vectorizer já configurado (tabela documentos_embeddings já existe)")
          ;; Se não existir, criar o vectorizer
          (let [;; Tenta várias opções de hosts, com configuração mais completa
                hosts ["http://pgai-ollama-1:11434" "http://ollama:11434" "http://172.18.0.2:11434" "http://host.docker.internal:11434" "http://localhost:11434"]
                
                ;; Função auxiliar para tentar um único host
                try-host (fn [host]
                           (try
                             (let [vectorizer-sql (str "SELECT ai.create_vectorizer(
                                          'public.documentos'::regclass,
                                          destination => 'documentos_embeddings',
                                          embedding => ai.embedding_ollama('nomic-embed-text', 768, '{\"host\": \"" host "\"}'),
                                          chunking => ai.chunking_recursive_character_text_splitter('conteudo')
                                        )")]
                               (jdbc/execute! conn [vectorizer-sql])
                               {:success true, :host host})
                             (catch Exception e
                               {:success false, 
                                :error (.getMessage e), 
                                :host host})))
                
                ;; Tenta cada host em sequência
                result (loop [remaining-hosts hosts]
                         (if (empty? remaining-hosts)
                           {:success false, :error "Todos os hosts falharam"}
                           (let [host (first remaining-hosts)
                                 _ (println "🔄 Tentando configurar vectorizer com host:" host)
                                 result (try-host host)]
                             (if (:success result)
                               (do
                                 (println "✅ Vectorizer configurado com sucesso usando host:" host)
                                 result)
                               (do
                                 (println "⚠️ Falha ao configurar vectorizer com host" host ":" (:error result))
                                 (recur (rest remaining-hosts)))))))]
            (if (:success result)
              true
              (do 
                (println "❌ Falha em todas as tentativas de configurar o vectorizer")
                false))))
        (catch Exception e
          (println "❌ Erro ao configurar vectorizer:" (.getMessage e))
          false)
        (finally
          (.close conn))))
    false))

(defn insert-document!
  "Insere um documento no banco de dados"
  [titulo conteudo categoria]
  (if (check-postgres-connection)
    (let [conn (jdbc/get-connection db-spec)
          insert-sql "INSERT INTO documentos (titulo, conteudo, categoria) 
                      VALUES (?, ?, ?) 
                      RETURNING id"]
      (try
        (let [result (jdbc/execute-one! conn [insert-sql titulo conteudo categoria]
                                       {:return-keys true
                                        :builder-fn rs/as-unqualified-maps})]
          (println "✅ Documento inserido com ID:" (:id result))
          (:id result))
        (catch Exception e
          (println "❌ Erro ao inserir documento:" (.getMessage e))
          nil)
        (finally
          (.close conn))))
    nil))

(defn import-markdown-file!
  "Importa um arquivo markdown para o banco de dados"
  [filepath categoria]
  (try
    (let [content (slurp filepath)
          titulo (-> filepath
                     (str/split #"/")
                     last
                     (str/replace #"\.md$" ""))
          id (insert-document! titulo content categoria)]
      (when id
        (println "✅ Arquivo importado com sucesso:" filepath)))
    (catch Exception e
      (println "❌ Erro ao importar arquivo:" (.getMessage e)))))

(defn semantic-search
  "Realiza busca semântica via PostgreSQL e pgvector"
  [query limit]
  (println "DEBUG - Iniciando busca semântica para query:" query "com limite:" limit)
  (if (check-postgres-connection)
    (let [conn (jdbc/get-connection db-spec)]
      (try
        (println "🔄 Executando busca semântica direta (sem host explícito)")
        ;; Primeiro tenta com a URL precisa, depois tem fallbacks
        (try
          (let [search-sql-direct "WITH query_embedding AS (
                       SELECT ai.ollama_embed('nomic-embed-text', ?) AS embedding
                     )
                     SELECT
                       d.id,
                       d.titulo,
                       d.conteudo,
                       d.categoria,
                       t.embedding <=> (SELECT embedding FROM query_embedding) AS distancia
                     FROM documentos_embeddings t
                     LEFT JOIN documentos d ON t.id = d.id
                     ORDER BY distancia
                     LIMIT ?"
                results (jdbc/execute! conn [search-sql-direct query limit]
                                      {:builder-fn rs/as-unqualified-maps})]
            results)
          (catch Exception e
            (println "⚠️ Erro na primeira tentativa:" (.getMessage e))
            (println "🔄 Tentando abordagem alternativa com host explícito...")
            
            ;; Se a primeira abordagem falhar, tenta com host específico
            (let [search-sql-with-host "WITH query_embedding AS (
                       SELECT ai.ollama_embed('nomic-embed-text', ?, host => ?) AS embedding
                     )
                     SELECT
                       d.id,
                       d.titulo,
                       d.conteudo,
                       d.categoria,
                       t.embedding <=> (SELECT embedding FROM query_embedding) AS distancia
                     FROM documentos_embeddings t
                     LEFT JOIN documentos d ON t.id = d.id
                     ORDER BY distancia
                     LIMIT ?"
                  
                  ;; Define a função que tentará um único host
                  try-host (fn [host]
                             (try
                               (let [result (jdbc/execute! conn [search-sql-with-host query host (* 3 limit)]
                                                         {:builder-fn rs/as-unqualified-maps})]
                                 {:success true, :result result})
                               (catch Exception err
                                 {:success false, 
                                  :error (.getMessage err), 
                                  :host host})))
                  
                  ;; Lista de hosts para tentar
                  hosts ["http://localhost:11434"]]
              
              ;; Tenta cada host em sequência
              (loop [remaining-hosts hosts
                     last-error nil]
                (if (empty? remaining-hosts)
                  (do
                    (println "❌ Todas as tentativas de conexão com Ollama falharam. Último erro:" last-error)
                    [])
                  (let [host (first remaining-hosts)
                        _ (println "🔄 Tentando conectar ao Ollama em" host)
                        result (try-host host)]
                    (if (and (:success result) (seq (:result result)))
                      (do
                        (println "✅ Conexão bem-sucedida com" host)
                        (when (seq (:result result))
                          (doseq [doc (:result result)]
                            (println "  - Documento:" (:id doc) "- Título:" (:titulo doc) "- Distância:" (:distancia doc))))
                        (:result result))
                      (recur (rest remaining-hosts) 
                             (or (:error result) 
                                 last-error 
                                 "Erro desconhecido")))))))))
        (catch Exception e
          (println "❌ Erro na busca semântica:" (.getMessage e))
          [])
        (finally
          (.close conn))))
    []))

(defn setup-pg-rag!
  "Configura todo o ambiente PostgreSQL para RAG"
  []
  (println "🚀 Configurando PostgreSQL para RAG...")
  (if (check-postgres-connection)
    (when (init-db!)
      (when (create-tables!)
        ;; Configura o Ollama para usar o endereço do Docker
        (llm/set-ollama-docker-mode!)
        (create-vectorizer!)))
    (println "⚠️ Avançando para modo de contingência sem PostgreSQL..."))) 