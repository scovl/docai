# Autenticação JWT em Clojure com integração Ollama

Este documento explica como implementar autenticação JWT (JSON Web Tokens) em uma aplicação Clojure que utiliza Ollama para inferência de modelos de linguagem.

## O que é JWT?

JWT (JSON Web Token) é um padrão aberto (RFC 7519) que define uma maneira compacta e autossuficiente para transmitir informações com segurança entre partes como um objeto JSON. Essas informações podem ser verificadas e confiáveis porque são assinadas digitalmente.

## Instalação das dependências

Adicione as bibliotecas necessárias ao seu projeto Clojure. No arquivo `project.clj`:

```clojure
[buddy/buddy-auth "3.0.1"]     ; Para autenticação e JWT
[buddy/buddy-sign "3.4.333"]   ; Para assinatura de tokens
[http-kit "2.6.0"]             ; Para requisições HTTP (Ollama API)
[org.clojure/data.json "2.4.0"] ; Para processamento JSON
```

## Criando tokens JWT

Para criar um token JWT, você precisa:

1. Definir os claims (afirmações) do token
2. Assinar o token com uma chave secreta ou chave privada

```clojure
(ns my-app.auth
  (:require [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as keys]))

;; Opção 1: Usando uma string secreta
(def secret "meu-segredo-muito-seguro")

;; Opção 2: Usando chaves assimétricas (mais seguro)
(def privkey (keys/private-key "path/to/privkey.pem"))
(def pubkey (keys/public-key "path/to/pubkey.pem"))

;; Função para criar um token JWT
(defn create-token [user]
  (let [claims {:user-id (:id user)
                :username (:username user)
                :roles (:roles user)
                :exp (+ (quot (System/currentTimeMillis) 1000) 
                        (* 60 60 24))}]  ; Expira em 24 horas
    (jwt/sign claims secret {:alg :hs256})))

;; Alternativa com chaves assimétricas
(defn create-token-rsa [user]
  (let [claims {:user-id (:id user)
                :username (:username user)
                :roles (:roles user)
                :exp (+ (quot (System/currentTimeMillis) 1000) 
                        (* 60 60 24))}]
    (jwt/sign claims privkey {:alg :rs256})))
```

## Verificando tokens

Para verificar um token JWT:

```clojure
(defn verify-token [token]
  (try
    (jwt/unsign token secret {:alg :hs256})
    (catch Exception e
      nil)))

;; Alternativa com chaves assimétricas
(defn verify-token-rsa [token]
  (try
    (jwt/unsign token pubkey {:alg :rs256})
    (catch Exception e
      nil)))
```

## Integrando com Ollama em um sistema RAG

Para integrar autenticação JWT com Ollama em um sistema RAG (Retrieval-Augmented Generation), podemos criar um middleware que protege as rotas de acesso ao LLM:

```clojure
(ns my-app.ollama
  (:require [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [my-app.auth :as auth]
            [buddy.auth :refer [authenticated?]]))

(def ollama-url "http://localhost:11434/api/generate")
(def model-name "deepseek-r1")

(defn call-ollama-api [prompt]
  "Chama a API do Ollama para gerar uma resposta"
  (let [request-body {:model model-name
                      :prompt prompt
                      :stream false}
        options {:headers {"Content-Type" "application/json"}
                 :body (json/write-str request-body)}
        response @(http/post ollama-url options)]
    (if (= (:status response) 200)
      (-> response
          :body
          (json/read-str :key-fn keyword)
          :response)
      (str "Erro ao chamar a API do Ollama: " (:status response)))))

(defn secure-rag-query [request]
  "Função segura que só permite consultas RAG para usuários autenticados"
  (if (authenticated? request)
    (let [user-identity (:identity request)
          query (get-in request [:params :query])
          context (get-relevant-context query)  ; Função que recupera contexto relevante
          prompt (format-prompt context query)
          response (call-ollama-api prompt)]
      {:status 200
       :body {:answer response
              :user user-identity}})
    {:status 401
     :body {:error "Acesso não autorizado"}}))
```

## Configurando rotas protegidas

Vamos criar rotas que utilizem autenticação JWT para proteger o acesso ao Ollama:

```clojure
(ns my-app.routes
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [my-app.middleware :refer [wrap-jwt-auth wrap-auth-required]]
            [my-app.auth :as auth]
            [my-app.ollama :as ollama]
            [my-app.db :as db]))

(defn login-handler [request]
  (let [body (:body request)
        username (:username body)
        password (:password body)
        user (db/authenticate username password)]
    (if user
      {:status 200
       :body {:token (auth/create-token user)}}
      {:status 401
       :body {:error "Credenciais inválidas"}})))

(defroutes app-routes
  ;; Rota de login - não requer autenticação
  (POST "/api/login" [] login-handler)
  
  ;; Rota de consulta RAG protegida - requer autenticação JWT
  (POST "/api/rag/query" [] (wrap-auth-required ollama/secure-rag-query))
  
  ;; Outras rotas...
  (route/not-found {:error "Rota não encontrada"}))

(def app
  (-> app-routes
      wrap-jwt-auth
      wrap-json-body
      wrap-json-response))
```

## Logs de auditoria para chamadas Ollama

Para manter um log de auditoria de quais usuários estão realizando quais consultas ao Ollama, podemos implementar um middleware de logging:

```clojure
(ns my-app.logging
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]))

(defn log-ollama-request [user query response]
  (log/info (str "Usuário: " (:username user) 
                " | ID: " (:user-id user)
                " | Query: " query
                " | Tamanho da resposta: " (count response) " caracteres")))

(defn wrap-ollama-audit-log [handler]
  (fn [request]
    (let [user (:identity request)
          query (get-in request [:params :query])
          response (handler request)]
      ;; Logamos apenas requisições bem-sucedidas
      (when (= (:status response) 200)
        (log-ollama-request user query (get-in response [:body :answer])))
      response)))
```

## Limitação de uso por perfil

Podemos implementar um sistema para limitar o acesso ao Ollama baseado no perfil do usuário:

```clojure
(ns my-app.rate-limit
  (:require [buddy.auth :refer [authenticated?]]))

(def rate-limits
  {:admin {:queries-per-day 1000 :max-tokens 4096}
   :premium {:queries-per-day 100 :max-tokens 2048}
   :basic {:queries-per-day 10 :max-tokens 1024}
   :guest {:queries-per-day 3 :max-tokens 512}})

(defn get-user-limit [user]
  (let [role (or (first (:roles user)) :guest)]
    (get rate-limits role (:guest rate-limits))))

(defn check-rate-limit [user]
  (let [user-id (:user-id user)
        current-usage (get-user-current-usage user-id)  ; Função para obter uso atual
        limits (get-user-limit user)]
    (if (< current-usage (:queries-per-day limits))
      {:allowed true :remaining (- (:queries-per-day limits) current-usage)}
      {:allowed false :reason "Limite diário excedido"})))

(defn wrap-ollama-rate-limit [handler]
  (fn [request]
    (if (authenticated? request)
      (let [user (:identity request)
            rate-check (check-rate-limit user)]
        (if (:allowed rate-check)
          (handler request)
          {:status 429
           :body {:error (:reason rate-check)
                  :limit (get-user-limit user)}}))
      {:status 401
       :body {:error "Acesso não autorizado"}})))
```

## Exemplo completo de um sistema RAG com Ollama e JWT

Aqui está um exemplo completo que mostra como integrar JWT, Ollama e um sistema RAG:

```clojure
(ns jwt-ollama-example.core
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [buddy.auth.backends.token :refer [token-backend]]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]))

;; ----- Auth JWT -----

(def secret "my-secret-key")

(defn create-token [user]
  (let [claims {:user (:username user)
                :roles (:roles user)
                :exp (+ (quot (System/currentTimeMillis) 1000) 
                        (* 60 30))}]  ; 30 minutos
    (jwt/sign claims secret {:alg :hs256})))

(defn verify-token [token]
  (try
    (jwt/unsign token secret {:alg :hs256})
    (catch Exception e
      nil)))

;; ----- Ollama API -----

(def ollama-url "http://localhost:11434/api/generate")
(def model-name "deepseek-r1")

(defn call-ollama-api [prompt]
  (let [request-body {:model model-name
                      :prompt prompt
                      :stream false}
        options {:headers {"Content-Type" "application/json"}
                 :body (json/write-str request-body)}
        response @(http/post ollama-url options)]
    (if (= (:status response) 200)
      (-> response
          :body
          (json/read-str :key-fn keyword)
          :response)
      (str "Erro ao chamar a API do Ollama: " (:status response)))))

;; ----- RAG System -----

(def docs (atom [{:id 1 :content "JWT é um padrão para autenticação baseado em tokens."}
                {:id 2 :content "Ollama é uma ferramenta para rodar LLMs localmente."}
                {:id 3 :content "RAG significa Retrieval-Augmented Generation."}]))

(defn search-docs [query]
  (let [matching-docs (filter #(re-find (re-pattern (str "(?i)" query)) (:content %)) @docs)]
    (map :content matching-docs)))

(defn rag-query [query]
  (let [context (search-docs query)
        prompt (str "Contexto: " (clojure.string/join " " context) 
                    "\n\nPergunta: " query 
                    "\n\nResposta:")]
    (call-ollama-api prompt)))

;; ----- Auth Middleware -----

(def auth-backend 
  (token-backend {:authfn (fn [request token]
                            (verify-token token))}))

(defn wrap-jwt-auth [handler]
  (wrap-authentication handler auth-backend))

;; ----- Handlers -----

(defn login-handler [request]
  (let [username (get-in request [:body "username"])
        password (get-in request [:body "password"])
        ;; Simplificação: apenas comparação de strings
        user (if (and (= username "admin") (= password "admin123"))
               {:id 1 :username "admin" :roles [:admin]}
               nil)]
    (if user
      {:status 200
       :body {:token (create-token user)}}
      {:status 401
       :body {:error "Credenciais inválidas"}})))

(defn rag-handler [request]
  (let [auth-header (get-in request [:headers "authorization"])
        token (when auth-header
                (second (re-find #"^Bearer (.+)$" auth-header)))
        claims (when token (verify-token token))]
    (if claims
      (let [query (get-in request [:body "query"])]
        {:status 200
         :body {:answer (rag-query query)
                :user (:user claims)}})
      {:status 401
       :body {:error "Não autorizado"}})))

;; ----- Routes -----

(defroutes app-routes
  (POST "/login" [] login-handler)
  (POST "/rag/query" [] rag-handler)
  (route/not-found {:error "Rota não encontrada"}))

(def app
  (-> app-routes
      wrap-jwt-auth
      wrap-json-body
      wrap-json-response
      wrap-params))

(defn -main []
  (println "🚀 Iniciando servidor na porta 3000")
  (println "ℹ️ Certifique-se de que o Ollama está em execução com: ollama serve")
  (println "ℹ️ Usando o modelo deepseek-r1. Se necessário, execute: ollama pull deepseek-r1")
  (jetty/run-jetty app {:port 3000}))
```

## Como testar a integração

Para testar a integração do JWT com Ollama em um sistema RAG:

1. Certifique-se de que o Ollama está em execução:
```bash
ollama serve
```

2. Inicie a aplicação:
```bash
lein run
```

3. Faça login para obter um token JWT:
```bash
curl -X POST http://localhost:3000/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

4. Utilize o token para fazer consultas RAG protegidas:
```bash
curl -X POST http://localhost:3000/rag/query \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SEU_TOKEN_JWT" \
  -d '{"query":"O que é JWT?"}'
```

## Conclusão

A combinação de autenticação JWT com sistemas RAG baseados em Ollama oferece uma solução poderosa para criar assistentes de IA seguros, controlados e personalizados. A autenticação JWT permite definir permissões granulares por usuário, enquanto o Ollama permite executar modelos de linguagem localmente sem dependência de APIs externas.

Esse padrão de implementação é ideal para:
- Aplicações corporativas com informações sensíveis
- Assistentes de IA que precisam acessar dados privados
- Sistemas que exigem auditoria rigorosa de acesso
- Ambientes onde a privacidade de dados é prioridade 