(ns athens.self-hosted.web.api
  (:require
    [athens.common-db :as common-db]
    [athens.common-events :as common-events]
    [athens.common-events.bfs :as bfs]
    [athens.common-events.graph.composite :as composite-ops]
    [athens.common-events.schema :as schema]
    [athens.self-hosted.clients :as clients]
    [athens.self-hosted.web.datascript :as web.datascript]
    [clojure.set :as set]
    [clojure.string :as str]
    [compojure.core :as c]
    [muuntaja.middleware :as muuntaja.mw]
    [ring.middleware.basic-authentication :as basic-auth]))


;; Helpers

(def fragment-k->eid-k
  {:page/title :node/title
   :block/uid  :block/uid})


(defn fragment->eid
  [x]
  (-> x
      (set/rename-keys fragment-k->eid-k)
      (select-keys (vals fragment-k->eid-k))
      vec
      first))


(def eid-k-map
  {:node/title :page/title
   :block/uid :block/uid})


(defn eid->position-id
  [[k v]]
  [(eid-k-map k) v])


;; Different from graph.ops/build-path, paths are [eid selectors...]
;; TODO: support order/prop selectors
(defn path->eid
  [_db [x & _selectors]]
  (fragment->eid x))


;; Path examples:
;; [{:page/title "page"}]
;; [{:block/uid "uid"}]
(defn read-path
  [conn path]
  (let [db @conn]
    (->> path
         (path->eid db)
         (common-db/get-internal-representation db))))


;; TODO: follow/create selectors
(defn create-path
  [_db [x & _selectors]]
  [(fragment->eid x) []])


(defn write-in-path-evt
  [conn path relation data]
  (let [db             @conn
        [eid path-ops] (create-path db path)
        default-pos    (into {:relation (or relation :last)}
                             [(eid->position-id eid)])
        write-ops      (bfs/internal-representation->atomic-ops db data default-pos)]
    (->> (into path-ops write-ops)
         (composite-ops/make-consequence-op {:op/type :path/write})
         common-events/build-atomic-event)))


(defn add-presence-id
  [presence-id event]
  (common-events/add-presence event presence-id))


(defn process-event!
  [datascript fluree config evt]
  (when-not (schema/valid-event? evt)
    (throw (ex-info "Invalid event" (schema/explain-event evt))))
  (when (->> evt
             (web.datascript/exec! datascript fluree config)
             :event/status
             (= :accepted))
    (clients/broadcast! evt)
    evt))


(defn ok
  [x]
  {:status 200
   :body x})


;; for convenience with ->>
(defn ret-first
  [x _]
  x)


;; Username is always required non-empty
(defn authenticated?
  [config-pw username pw]
  (if (and (not (str/blank? username))
           (or (not config-pw)
               (= pw config-pw)))
    {:presence-id username}
    false))


;; Routes with inline handlers.

(defn make-routes
  [datascript fluree config]
  (let [conn (:conn datascript)
        ;; TODO: take presence-id from basic auth username
        config-pw (-> config :config :password)]
    (if-not (-> config :config :feature-flags :api)
      (c/routes)
      (->
        (c/routes
          (c/context
            "/api/path" []

            (c/POST
              "/read" {{:keys [path]} :body-params}
              (->> path
                   (read-path conn)
                   ok))

            (c/POST
              "/write" {{:keys [path relation data]} :body-params
                        {:keys [presence-id]}        :basic-authentication}
              (->> (write-in-path-evt conn path relation data)
                   (add-presence-id presence-id)
                   (process-event! datascript fluree config)
                   (ret-first path)
                   (read-path conn)
                   ok))))
        (basic-auth/wrap-basic-authentication (partial authenticated? config-pw))
        muuntaja.mw/wrap-format))))


;; curl examples
;; read
;; curl -u api-test: -H "Content-Type: application/edn" -H "Accept: application/edn" -X POST localhost:3010/api/path/read -d '{:path [{:page/title "page"}]}'
;; curl -u api-test: -H "Content-Type: application/json" -X POST localhost:3010/api/path/read -d '{"path":[{"page/title":"page"}]}'
;; write
;; curl -u api-test: -H "Content-Type: application/edn" -H "Accept: application/edn" -X POST localhost:3010/api/path/write -d '{:path [{:page/title "page"}] :data [{:block/string "one" :block/children [{:block/string "two"}]}]}'
;; curl -u api-test: -H "Content-Type: application/json" -X POST localhost:3010/api/path/write -d '{"path":[{"page/title":"page"}], "data":[{"block/string":"one", "block/children":[{"block/string":"two"}]}]}'
;; auth
;; userrname always needed even if empty/no pw
;; curl -u presence-name:server-password ...
