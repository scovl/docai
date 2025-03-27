(ns docai.advanced-rag
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [docai.llm :as llm]
            [docai.pg :as pg]))

;; Cache para embeddings
(def embedding-cache (atom {}))
(def response-cache (atom {}))

;; Configuração de cache
(def cache-ttl (* 60 60 24)) ;; 24 horas em segundos
(def max-cache-size 1000)

;; Pré-declaração de funções para evitar referências circulares
(declare advanced-rag-query advanced-semantic-search analyze-query-complexity personal-query? sliding-window-chunks dynamic-semantic-search)

(defn cached-embedding
  "Gera embedding para texto com cache"
  [text db-spec]
  (if-let [cached (@embedding-cache text)]
    (do
      (println "Cache hit para embedding!")
      cached)
    (let [conn (jdbc/get-connection db-spec)
          embedding-sql "SELECT ai.ollama_embed('nomic-embed-text', ?) AS embedding"
          result (try
                   (jdbc/execute-one! conn [embedding-sql text]
                                     {:builder-fn rs/as-unqualified-maps})
                   (catch Exception e
                     (println "Erro ao gerar embedding:" (.getMessage e))
                     nil))
          embedding (:embedding result)]
      (.close conn)
      (when embedding
        (swap! embedding-cache 
               (fn [cache]
                 (let [updated-cache (assoc cache text embedding)]
                   ;; Limitar tamanho do cache
                   (if (> (count updated-cache) max-cache-size)
                     (into {} (take max-cache-size updated-cache))
                     updated-cache)))))
      embedding)))

(defn ^:private cached-rag-query
  "Executa consulta RAG com cache ou armazena o resultado no cache"
  [query response]
  ;; Armazenar no cache apenas para consultas não-pessoais
  (when (not (personal-query? query))
    (swap! response-cache 
           (fn [cache]
             (let [updated-cache (assoc cache query {:response response
                                                    :timestamp (System/currentTimeMillis)})]
               ;; Limitar tamanho do cache
               (if (> (count updated-cache) max-cache-size)
                 (into {} (take max-cache-size updated-cache))
                 updated-cache)))))
  response)

;; Função principal para processamento RAG avançado
(defn advanced-rag-query
  "Processa uma consulta usando RAG avançado"
  [query]
  (let [;; Verificar cache primeiro
        cached (get @response-cache query)
        current-time (System/currentTimeMillis)]
    
    (if (and cached (< (- current-time (:timestamp cached)) (* cache-ttl 1000)))
      ;; Retornar do cache se disponível e não expirado
      (:response cached)
      
      ;; Caso contrário, processar a consulta
      (let [;; Analisar complexidade da consulta
            query-complexity (analyze-query-complexity query)
            start-time (System/currentTimeMillis)]
        
        (println "Complexidade da consulta:" query-complexity)
        
        (let [;; Usar busca semântica com chunking dinâmico
              docs (dynamic-semantic-search query 5)
              ;; Fallback para busca semântica padrão se não houver resultados dinâmicos
              final-docs (if (seq docs)
                           docs
                           (advanced-semantic-search query 5))
              context (->> final-docs
                         (map :content)
                         (str/join "\n\n"))
              prompt (llm/format-prompt context query)
              response (llm/call-ollama-api prompt)
              
              ;; Calcular tempo total
              total-time (- (System/currentTimeMillis) start-time)]
          
          ;; Log para análise
          (println "Consulta processada em" total-time "ms")
          
          ;; Atualizar cache usando a função auxiliar
          (cached-rag-query query response))))))

(defn personal-query?
  "Verifica se a consulta contém informações pessoais que não devem ser cacheadas"
  [query]
  (let [lower-query (str/lower-case query)
        personal-patterns ["meu" "minha" "nosso" "nossa" "eu" "nós"]]
    (some #(str/includes? lower-query %) personal-patterns)))

(defn cleanup-cache
  "Remove entradas expiradas do cache"
  []
  (let [current-time (System/currentTimeMillis)
        expiry-time-ms (* cache-ttl 1000)]
    (swap! response-cache
           (fn [cache]
             (into {} (filter (fn [[_ v]]
                                (< (- current-time (:timestamp v)) expiry-time-ms))
                              cache))))))

;; Estratégias de chunking
(defn adaptive-chunking-strategy
  "Determina estratégia de chunking com base no tipo de documento"
  [document-type]
  (case document-type
    "article" {:chunk-size 1000 :chunk-overlap 150}
    "code" {:chunk-size 500 :chunk-overlap 50}
    "legal" {:chunk-size 1500 :chunk-overlap 200}
    "qa" {:chunk-size 800 :chunk-overlap 100}
    ;; Default
    {:chunk-size 1000 :chunk-overlap 100}))

(defn sliding-window-chunks
  "Divide texto em chunks de tamanho fixo com sobreposição"
  [text chunk-size chunk-overlap]
  (let [stride (- chunk-size chunk-overlap)]
    (loop [start 0
           result []]
      (if (>= start (count text))
        result
        (let [end (min (+ start chunk-size) (count text))
              chunk (subs text start end)]
          (recur (+ start stride)
                 (conj result chunk)))))))

(defn recursive-text-splitter
  "Implementação de chunking recursivo que respeita a estrutura do documento"
  [text {:keys [chunk-size chunk-overlap]}]
  (let [;; Tentar dividir por seções (cabeçalhos)
        sections (re-seq #"(?m)^#{1,6}\s+.*$\n(?:(?!^#{1,6}\s+).*\n)*" text)]
    (if (and sections (> (count sections) 1))
      ;; Se conseguiu dividir por seções, processar cada seção
      (mapcat #(recursive-text-splitter % {:chunk-size chunk-size :chunk-overlap chunk-overlap}) sections)
      ;; Caso contrário, dividir por parágrafos
      (let [paragraphs (re-seq #"(?m)^.*(?:\n(?!\s*\n).*)*" text)]
        (if (and paragraphs (> (count paragraphs) 1))
          ;; Se conseguiu dividir por parágrafos, processar cada parágrafo grande
          (mapcat (fn [p]
                    (if (> (count p) chunk-size)
                      ;; Dividir parágrafos grandes em chunks com sobreposição
                      (sliding-window-chunks p chunk-size chunk-overlap)
                      [p]))
                  paragraphs)
          ;; Último recurso: divisão por tamanho fixo
          (sliding-window-chunks text chunk-size chunk-overlap))))))

(defn dynamic-chunk-document
  "Processa um documento usando chunking dinâmico adaptado ao tipo de conteúdo"
  [document-id content document-type]
  (let [;; Obter estratégia de chunking apropriada para o tipo de documento
        chunking-params (adaptive-chunking-strategy document-type)
        ;; Aplicar chunking dinâmico
        chunks (recursive-text-splitter content chunking-params)]
    
    (println "Documento " document-id " dividido em " (count chunks) " chunks usando estratégia para tipo " document-type)
    
    ;; Retornar chunks com metadados
    (map-indexed 
     (fn [idx chunk] 
       {:document_id document-id
        :chunk_id (str document-id "-" idx)
        :content chunk
        :chunk_type document-type
        :chunk_index idx
        :total_chunks (count chunks)})
     chunks)))

;; Reimplementação de vetorização com chunking adaptativo
(defn create-vectorizer-with-adaptive-chunking!
  "Configura vectorizer com chunking adaptativo"
  [document-type]
  (println "Configurando vectorizer para documento tipo:" document-type)
  (let [chunking-params (adaptive-chunking-strategy document-type)
        chunk-size (:chunk-size chunking-params)
        table-name (str "documentos_embeddings_" document-type)
        conn (jdbc/get-connection pg/db-spec)]
    
    (try
      ;; Tentar criar a tabela de embeddings
      (jdbc/execute! 
       conn
       [(str "CREATE TABLE IF NOT EXISTS " table-name " (
              id TEXT PRIMARY KEY,
              embedding VECTOR(768)
            )")])
      
      ;; Configurar vectorizer com chunking adaptativo
      (jdbc/execute!
       conn
       [(str "CREATE OR REPLACE FUNCTION ai.vectorize_" document-type "()\n"
             "RETURNS TRIGGER AS $$\n"
             "BEGIN\n"
             "  INSERT INTO " table-name " (id, embedding)\n"
             "  VALUES (NEW.id, ai.ollama_embed('nomic-embed-text', NEW.conteudo))\n"
             "  ON CONFLICT (id) DO UPDATE\n"
             "    SET embedding = ai.ollama_embed('nomic-embed-text', NEW.conteudo);\n"
             "  RETURN NEW;\n"
             "END;\n"
             "$$ LANGUAGE plpgsql;")])
      
      ;; Criar o trigger
      (jdbc/execute!
       conn
       [(str "DROP TRIGGER IF EXISTS vectorize_" document-type " ON documentos;")]
       {:return-keys false})
      
      (jdbc/execute!
       conn
       [(str "CREATE TRIGGER vectorize_" document-type "\n"
             "AFTER INSERT OR UPDATE ON documentos\n"
             "FOR EACH ROW\n"
             "WHEN (NEW.categoria = '" document-type "')\n"
             "EXECUTE FUNCTION ai.vectorize_" document-type "();")]
       {:return-keys false})
      
      (println "✅ Vectorizer para documentos tipo" document-type "configurado com sucesso!\n"
               "   Usando chunk_size:" chunk-size)
      (catch Exception e
        (println "⚠️ Aviso: Erro ao configurar vectorizer para" document-type ":" (.getMessage e)))
      (finally
        (.close conn)))))

;; Função para processar um documento com chunking dinâmico e inserir no banco
(defn process-document-with-dynamic-chunking!
  "Processa um documento com chunking dinâmico e insere os chunks no banco de dados"
  [document-id content document-type]
  (let [;; Gerar chunks usando chunking dinâmico
        chunks (dynamic-chunk-document document-id content document-type)
        conn (jdbc/get-connection pg/db-spec)
        
        ;; Verificar se a tabela de chunks existe, criar se necessário
        _ (try
            (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS document_chunks (
                                 id SERIAL PRIMARY KEY,
                                 document_id TEXT NOT NULL,
                                 chunk_id TEXT NOT NULL UNIQUE,
                                 content TEXT NOT NULL,
                                 chunk_type TEXT NOT NULL,
                                 chunk_index INTEGER NOT NULL,
                                 total_chunks INTEGER NOT NULL,
                                 embedding VECTOR(768) NULL,
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                               )"])
            (catch Exception e
              (println "Aviso: Erro ao criar tabela de chunks:" (.getMessage e))))]
    
    ;; Inserir cada chunk no banco
    (doseq [chunk chunks]
      (try
        (jdbc/execute! conn ["INSERT INTO document_chunks 
                             (document_id, chunk_id, content, chunk_type, chunk_index, total_chunks)
                             VALUES (?, ?, ?, ?, ?, ?)
                             ON CONFLICT (chunk_id) DO NOTHING"
                           (:document_id chunk)
                           (:chunk_id chunk)
                           (:content chunk)
                           (:chunk_type chunk)
                           (:chunk_index chunk)
                           (:total_chunks chunk)])
        (catch Exception e
          (println "Erro ao inserir chunk" (:chunk_id chunk) ":" (.getMessage e)))))
    
    ;; Gerar embeddings para os novos chunks
    (println "Gerando embeddings para chunks do documento" document-id "...")
    (try
      (jdbc/execute! conn ["UPDATE document_chunks 
                           SET embedding = ai.ollama_embed('nomic-embed-text', content) 
                           WHERE document_id = ? AND embedding IS NULL"
                         document-id])
      (catch Exception e
        (println "Erro ao gerar embeddings:" (.getMessage e))))
    
    (.close conn)
    
    (println "✅ Documento" document-id "processado com chunking dinâmico e armazenado com sucesso.")
    (count chunks)))

;; Função avançada para busca semântica usando os chunks dinâmicos
(defn dynamic-semantic-search
  "Realiza busca semântica em chunks gerados dinamicamente"
  [query limit]
  (let [conn (jdbc/get-connection pg/db-spec)
        ;; Gerar embedding para a consulta
        query-embedding (cached-embedding query pg/db-spec)
        
        ;; Buscar chunks mais próximos
        results (when query-embedding
                  (try
                    (jdbc/execute! 
                     conn
                     ["SELECT 
                       c.document_id,
                       c.chunk_id,
                       c.content,
                       c.chunk_type,
                       c.chunk_index,
                       c.total_chunks,
                       1 - (c.embedding <=> ?) AS similarity
                       FROM document_chunks c
                       WHERE c.embedding IS NOT NULL
                       ORDER BY c.embedding <=> ?
                       LIMIT ?"
                      query-embedding
                      query-embedding
                      limit]
                     {:builder-fn rs/as-unqualified-maps})
                    (catch Exception e
                      (println "Erro na busca semântica dinâmica:" (.getMessage e))
                      [])))]
    
    (.close conn)
    results))

;; Implementação de reranking
(defn rerank-results
  "Re-classifica resultados usando scoring avançado"
  [query initial-results]
  (let [;; Calcular scores mais sofisticados para cada resultado
        results-with-scores (map 
                             (fn [doc]
                               (let [;; Combina múltiplos fatores na pontuação
                                     ;; 1. Distância original de embedding (já temos)
                                     emb-score (:distancia doc)
                                     
                                     ;; 2. Correspondência de palavras-chave
                                     query-words (-> query
                                                   str/lower-case
                                                   (str/split #"\s+")
                                                   set)
                                     content-lower (str/lower-case (:conteudo doc))
                                     keyword-matches (count (filter #(str/includes? content-lower %) query-words))
                                     keyword-score (/ keyword-matches (max 1 (count query-words)))
                                     
                                     ;; 3. Comprimento do documento (preferência para respostas mais concisas)
                                     length-factor (Math/max 0.5 (Math/min 1.0 (/ 2000.0 (count (:conteudo doc)))))
                                     
                                     ;; Score combinado (quanto menor, melhor)
                                     combined-score (+ (* 0.6 emb-score)
                                                      (* -0.3 keyword-score) ;; Negativo porque queremos maximizar correspondências
                                                      (* 0.1 (- 1.0 length-factor)))]
                                 (assoc doc :relevance_score combined-score)))
                             initial-results)
        
        ;; Ordenar por score combinado (do menor para o maior)
        reranked-results (sort-by :relevance_score results-with-scores)]
    
    reranked-results))

;; Busca semântica avançada com reranking
(defn advanced-semantic-search
  "Realiza busca semântica avançada com re-ranqueamento"
  [query limit]
  (let [conn (jdbc/get-connection pg/db-spec)
        ;; Etapa 1: Recuperação inicial usando embeddings
        initial-results (try
                          (jdbc/execute! 
                           conn
                           ["WITH query_embedding AS (
                              SELECT ai.ollama_embed('nomic-embed-text', ?) AS embedding
                            )
                            SELECT
                              d.id,
                              d.titulo,
                              d.conteudo,
                              d.categoria,
                              e.embedding <=> (SELECT embedding FROM query_embedding) AS distancia
                            FROM documentos d
                            JOIN documentos_embeddings e ON d.id = e.id
                            ORDER BY distancia
                            LIMIT 20"
                            query]
                           {:builder-fn rs/as-unqualified-maps})
                          (catch Exception e
                            (println "Erro na busca inicial:" (.getMessage e))
                            []))
        
        ;; Etapa 2: Re-ranqueamento para melhorar precisão
        reranked-results (if (> (count initial-results) 1)
                           (rerank-results query initial-results)
                           initial-results)]
    
    (.close conn)
    
    ;; Retornar apenas o número solicitado de resultados
    (take limit reranked-results)))

;; Análise de complexidade da consulta
(defn analyze-query-complexity
  "Analisa a complexidade da consulta para escolher a estratégia adequada"
  [query]
  (let [lower-query (str/lower-case query)
        ;; Padrões que indicam consultas complexas
        complex-patterns ["como" "explique" "compare" "diferença entre" "quando devo"
                          "melhor maneira" "vantagens e desvantagens" "pros e contras"]
        ;; Padrões que indicam necessidade de dados estruturados
        structured-patterns ["tabela" "lista" "valor" "número" "quantidade" 
                             "estatística" "percentual" "quanto" "valores"]]
    (cond
      (some #(str/includes? lower-query %) complex-patterns) :complex
      (some #(str/includes? lower-query %) structured-patterns) :structured-data
      :else :simple)))

(defn setup-advanced-rag!
  "Configura o ambiente para RAG avançado"
  []
  (println "Configurando ambiente para RAG avançado...")
  
  ;; Verificar se o PostgreSQL está acessível
  (if (pg/check-postgres-connection)
    (do
      ;; Configurar vectorizadores para diferentes tipos de documentos
      (create-vectorizer-with-adaptive-chunking! "article")
      (create-vectorizer-with-adaptive-chunking! "code")
      
      ;; Criar tabela para chunks dinâmicos
      (let [conn (jdbc/get-connection pg/db-spec)]
        (try
          (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS document_chunks (
                               id SERIAL PRIMARY KEY,
                               document_id TEXT NOT NULL,
                               chunk_id TEXT NOT NULL UNIQUE,
                               content TEXT NOT NULL, 
                               chunk_type TEXT NOT NULL,
                               chunk_index INTEGER NOT NULL,
                               total_chunks INTEGER NOT NULL,
                               embedding VECTOR(768) NULL,
                               created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                             )"])
          (println "✅ Tabela para chunks dinâmicos configurada com sucesso")
          (catch Exception e
            (println "⚠️ Erro ao criar tabela de chunks dinâmicos:" (.getMessage e))))
        (.close conn))
      
      ;; Iniciar limpeza periódica de cache
      (future
        (while true
          (try
            (cleanup-cache)
            (catch Exception e
              (println "Erro na limpeza do cache:" (.getMessage e))))
          (Thread/sleep (* 60 60 1000)))) ;; Executar a cada hora
      
      (println "✅ RAG avançado configurado com sucesso!"))
    (println "❌ Falha na configuração do RAG avançado: PostgreSQL não acessível"))) 