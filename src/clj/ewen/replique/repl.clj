(ns ewen.replique.repl
  (:require [ewen.replique.browser-env :refer []]
            [ewen.replique.server :refer []]
            [cljs.repl :as repl]
            [cljs.repl.browser :as benv]
            [cljs.env :as env]
            [clojure.tools.nrepl
             [transport :as t]
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [interruptible-eval *msg*]]

            [clojure.tools.nrepl.middleware.session :refer [session]]
            [clojure.tools.nrepl.middleware.load-file :refer [wrap-load-file]]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api])
  (:import (java.io StringReader Writer)
           (clojure.lang LineNumberingPushbackReader)))





(defonce started-cljs-session (atom #{}))

;Use wrap-cljs-repl* as a var instead of a function to enable code reloading at the REPL.
;Maybe there is a better way?
(declare captured-h)
(declare captured-executor)

(defn evaluate
  [bindings {:keys [code ns transport session eval] :as msg}]
  (let [explicit-ns-binding (when-let [ns (and ns (-> ns symbol ana-api/find-ns))]
                              {#'ana/*cljs-ns* ns})
        original-ns (bindings #'ana/*cljs-ns*)
        maybe-restore-original-ns (fn [bindings]
                                    (if-not explicit-ns-binding
                                      bindings
                                      (assoc bindings #'ana/*cljs-ns* original-ns)))
        bindings (atom (merge bindings explicit-ns-binding))
        session (or session (atom nil))
        out (@bindings #'*out*)
        err (@bindings #'*err*)
        env (get @bindings #'ewen.replique.server/browser-env)]
    (if (and ns (not explicit-ns-binding))
      (t/send transport (response-for msg {:status #{:error :namespace-not-found :done}}))
      (with-bindings @bindings
        (try
          (cljs.repl/repl env
            :eval (if eval (find-var (symbol eval)) #'cljs.repl/eval-cljs)
            :read (if (string? code)
                    (let [reader (LineNumberingPushbackReader. (StringReader. code))]
                      #(read reader false %2))
                    (let [code (.iterator ^Iterable code)]
                      #(or (and (.hasNext code) (.next code)) %2)))
            :prompt (fn [])
            :need-prompt (constantly false)
            ; TODO pretty-print?
            :print (fn [v]
                     (.flush ^Writer err)
                     (.flush ^Writer out)
                     (reset! session (maybe-restore-original-ns @bindings))
                     (t/send transport (response-for msg
                                                     {:value (if (nil? v) "nil" v)
                                                      :ns    (-> ana/*cljs-ns* str)})))
            ; TODO customizable exception prints
            :caught (fn [e env opts]
                      (let [root-ex (#'clojure.main/root-cause e)]
                        (when-not (instance? ThreadDeath root-ex)
                          (reset! bindings (assoc (#'clojure.tools.nrepl.middleware.interruptible-eval/capture-thread-bindings) #'*e e))
                          (reset! session (maybe-restore-original-ns @bindings))
                          (t/send transport (response-for msg {:status :eval-error
                                                               :ex (-> e class str)
                                                               :root-ex (-> root-ex class str)}))
                          (cljs.repl/repl-caught e env opts)))))
          (finally
            (.flush ^Writer out)
            (.flush ^Writer err)))))
    (maybe-restore-original-ns @bindings)))

(defn cljs-repl*
  [{:keys [op session interrupt-id id transport] :as msg}]
  (cond (and (get @started-cljs-session (:id (meta session)))
             (= op "eval"))
        (if-not (:code msg)
          (t/send transport (response-for msg :status #{:error :no-code}))
          (#'clojure.tools.nrepl.middleware.interruptible-eval/queue-eval session captured-executor
            (fn []
              (alter-meta! session assoc
                           :thread (Thread/currentThread)
                           :eval-msg msg)
              (binding [*msg* msg]
                (evaluate @session msg)
                (t/send transport (response-for msg :status :done))
                (alter-meta! session dissoc :thread :eval-msg)))))

        (and (get @started-cljs-session (:id (meta session)))
             (= op "interrupt"))
        ; interrupts are inherently racy; we'll check the agent's :eval-msg's :id and
        ; bail if it's different than the one provided, but it's possible for
        ; that message's eval to finish and another to start before we send
        ; the interrupt / .stop.
        (let [{:keys [id eval-msg ^Thread thread]} (meta session)]
          (if (or (not interrupt-id)
                  (= interrupt-id (:id eval-msg)))
            (if-not thread
              (t/send transport (response-for msg :status #{:done :session-idle}))
              (do
                ; notify of the interrupted status before we .stop the thread so
                ; it is received before the standard :done status (thereby ensuring
                ; that is stays within the scope of a clojure.tools.nrepl/message seq
                (t/send transport {:status  #{:interrupted}
                                   :id      (:id eval-msg)
                                   :session id})
                (.stop thread)
                (t/send transport (response-for msg :status #{:done}))))
            (t/send transport (response-for msg :status #{:error :interrupt-id-mismatch :done}))))
        :esle (captured-h msg)))

(defn cljs-repl
  [h & {:keys [executor] :or {executor (#'clojure.tools.nrepl.middleware.interruptible-eval/configure-executor)}}]
  (def captured-h h)
  (def captured-executor executor)
  #'cljs-repl*)

(set-descriptor! #'cljs-repl
                 {:requires #{#'session #'ewen.replique.server/replique-server}
                  :expects #{#'interruptible-eval #'wrap-load-file}})


(comment



  (:id (meta (:session clojure.tools.nrepl.middleware.interruptible-eval/*msg*)))

  (swap! started-cljs-session conj "9f751a05-f036-4f9c-9bef-d5c6bffbaac7")
  (reset! started-cljs-session #{})



  )
