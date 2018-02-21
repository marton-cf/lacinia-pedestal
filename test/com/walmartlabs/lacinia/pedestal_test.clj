(ns com.walmartlabs.lacinia.pedestal-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia.pedestal :as lp :refer [inject]]
    [clj-http.client :as client]
    [clojure.string :as str]
    [com.walmartlabs.lacinia.test-utils :refer [test-server-fixture
                                                send-request
                                                send-json-request]]
    [clojure.spec.test.alpha :as stest])
  (:import (clojure.lang ExceptionInfo)))

(stest/instrument)

(use-fixtures :once (test-server-fixture {:graphiql true}
                                         (fn [schema]
                                           ;; Force things to work as they will in 0.8.0,
                                           ;; where the interceptors are a seq (not a map).
                                           {:interceptors (lp/default-interceptors schema nil)})))

(deftest simple-get-query
  (let [response (send-request "{ echo(value: \"hello\") { value method }}")]
    (is (= 200 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (= {:data {:echo {:method "get"
                          :value "hello"}}}
           (:body response)))))

(deftest simple-post-query
  (let [response (send-request :post "{ echo(value: \"hello\") { value method }}")]
    (is (= 200 (:status response)))
    (is (= {:data {:echo {:method "post"
                          :value "hello"}}}
           (:body response)))))

(deftest includes-content-type-check-on-post
  (let [response
        (send-json-request :post
                           {:query "{ echo(value: \"hello\") { value method }}"}
                           "text/plain")]
    (is (= {:body {:errors [{:message "Request content type must be application/graphql or application/json."}]}
            :status 400}
           (select-keys response [:status :body])))))

(deftest missing-query
  (let [response (send-json-request :get nil nil)]
    (is (= {:body {:errors [{:message "Query parameter 'query' is missing or blank."}]}
            :status 400}
           (select-keys response [:status :body])))))

(deftest empty-body
  (let [response (send-json-request :post nil "application/json")]
    (is (= {:body {:errors [{:message "Request body is empty."}]}
            :status 400}
           (select-keys response [:status :body])))))

(deftest can-handle-json
  (let [response
        (send-json-request :post
                           {:query "{ echo(value: \"hello\") { value method }}"})]
    (is (= 200 (:status response)))
    (is (= {:data {:echo {:method "post"
                          :value "hello"}}}
           (:body response)))))


(deftest can-handle-vars-json
  (let [response
        (send-json-request :post
                           {:query "query ($v: String) {
                                      echo(value: $v) { value }
                            }"
                            :variables {:v "Calculon"}})]
    (is (= {:body {:data {:echo {:value "Calculon"}}}
            :status 200}
           (select-keys response [:status :body])))))

(deftest can-handle-operation-name-json
  (let [response
        (send-json-request :post
                           {:query "query stuff($v: String) {
                                      echo(value: $v) { value }
                            }"
                            :variables {:v "Calculon"}
                            :operationName "stuff"})]
    (is (= {:body {:data {:echo {:value "Calculon"}}}
            :status 200}
           (select-keys response [:status :body])))))

(deftest status-set-by-error
  (let [response (send-request "{ echo(value: \"Baked.\", error: 420) { value }}")]
    (is (= {:body {:data {:echo {:value "Baked."}}
                   :errors [{:arguments {:error "420"
                                         :value "Baked."}
                             :locations [{:column 0
                                          :line 1}]
                             :message "Forced error."
                             :query-path ["echo"]}]}
            :status 420}
           (select-keys response [:status :body])))))

(deftest can-handle-vars
  (let [response (send-request :post "query ($v: String) {
    echo(value: $v) { value }
   }" {:v "Calculon"})]
    (is (= {:body {:data {:echo {:value "Calculon"}}}
            :status 200}
           (select-keys response [:status :body])))))

(deftest can-access-graphiql
  (let [response (client/get "http://localhost:8888/" {:throw-exceptions false})]
    (is (= 200 (:status response)))
    (is (str/includes? (:body response) "<html>"))))

(deftest forbids-subscriptions
  (let [response (send-request :post "subscription { ping(message: \"gnip\") { message }}")]
    (is (= {:body {:errors [{:message "Subscription queries must be processed by the WebSockets endpoint."}]}
            :status 400}
           (select-keys response [:status :body])))))

(deftest inject-not-found
  (is (thrown-with-msg? ExceptionInfo #"Could not find existing interceptor"
                        (inject [{:name :fred}] {:name :barney} :before :bam-bam))))

(deftest inject-before
  (let [fred {:name :fred}
        barney {:name :barney}
        wilma {:name :wilma}]
    (is (= [fred wilma barney]
           (inject [fred barney] wilma :before :barney)))))

(deftest inject-after
  (let [fred {:name :fred}
        barney {:name :barney}
        wilma {:name :wilma}]
    (is (= [fred barney wilma]
           (inject [fred barney] wilma :after :barney)))))

(deftest inject-replace
  (let [fred {:name :fred}
        barney {:name :barney}
        wilma {:name :wilma}]
    (is (= [fred wilma]
           (inject [fred barney] wilma :replace :barney)))))

(deftest inject-skips-fns
  (let [fred identity
        barney {:name :barney}
        wilma {:name :wilma}]
    (is (= [fred wilma]
           (inject [fred barney] wilma :replace :barney)))))
