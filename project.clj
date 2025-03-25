(defproject docai "0.1.0-SNAPSHOT"
  :description "Um assistente RAG para consulta de documentação técnica"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [markdown-to-hiccup "0.6.2"]    ; Para processar Markdown
                 [hickory "0.7.1"]              ; Para processar HTML
                 [org.clojure/data.json "2.4.0"]  ; Para JSON
                 [http-kit "2.6.0"]             ; Para requisições HTTP
                 [org.clojure/tools.logging "1.2.4"]  ; Para logging
                 [org.clojure/tools.namespace "1.4.4"]  ; Para reloading
                 [org.clojure/core.async "1.6.681"]  ; Para operações assíncronas
                 [org.clojure/core.memoize "1.0.257"]  ; Para cache
                 [org.clojure/core.cache "1.0.225"]  ; Para cache
                 [com.github.seancorfield/next.jdbc "1.3.1002"]  ; Para PostgreSQL
                 [org.postgresql/postgresql "42.7.2"]  ; Driver PostgreSQL
                 [camel-snake-kebab "0.4.3"]]  ; Para compatibilidade com next.jdbc
  :main ^:skip-aot docai.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:plugins [[lein-cljfmt "0.9.0"]  ; Para formatação de código
                            [lein-kibit "0.1.8"]]}})  ; Para revisão estática
