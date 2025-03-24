(ns docai.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [docai.core :as core]))

(deftest a-test
  (testing "Verificando se a função -main existe"
    (is (fn? core/-main))))
