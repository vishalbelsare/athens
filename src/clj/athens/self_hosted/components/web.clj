(ns athens.self-hosted.components.web
  (:require
    [athens.common-events              :as common-events]
    [athens.common-events.schema       :as schema]
    [athens.common.logging             :as log]
    [athens.self-hosted.clients        :as clients]
    [athens.self-hosted.web.datascript :as datascript]
    [athens.self-hosted.web.presence   :as presence]
    [com.stuartsierra.component        :as component]
    [compojure.core                    :as compojure]
    [org.httpkit.server                :as http]))


;; WebSocket handlers

(defn close-handler
  [channel status]
  (let [{:keys [username] :as session} (clients/get-client-session channel)]
    (clients/remove-client! channel)
    ;; Notify clients after removing the one that left.
    (presence/goodbye-handler session)
    (log/info "username:" (pr-str username) "!! closed connection, status:" (pr-str status))))


(defn- valid-event-handler
  "Processes valid event received from the client."
  [datascript fluree in-memory? server-password channel username {:event/keys [id type] :as data}]
  (if (and (false? username)
           (not= :presence/hello type))
    (do
      (log/warn "Message out of order, didn't say :presence/hello.")
      (clients/send! channel (common-events/build-event-rejected id
                                                                 :introduce-yourself
                                                                 {:protocol-error :client-not-introduced})))
    (if-let [result (cond
                      (contains? presence/supported-event-types type)
                      (presence/presence-handler (:conn datascript) server-password channel data)

                      (= :op/atomic type)
                      (datascript/atomic-op-handler (:conn datascript) fluree in-memory? channel data)

                      :else
                      (do
                        (log/error (pr-str username) "-> receive-handler, unsupported event:" (pr-str type))
                        (common-events/build-event-rejected id
                                                            (str "Unsupported event: " type)
                                                            {:unsupported-type type})))]
      (merge {:event/id id}
             result)
      (log/error "username:" (pr-str username) ", event-id:" (pr-str id) ", type:" (pr-str type) "No result for `valid-event-handler`"))))


(def ^:private forwardable-events
  #{:op/atomic})


(defn- make-receive-handler
  [datascript fluree in-memory? server-password]
  (fn receive-handler
    [channel msg]
    (let [username (clients/get-client-username channel)
          data     (clients/<-transit msg)]
      (if-not (schema/valid-event? data)
        (let [explanation (schema/explain-event data)]
          (log/warn "username:" username "Invalid event received, explanation:" explanation)
          (clients/send! channel (common-events/build-event-rejected (:event/id data)
                                                                     (str "Invalid event: " (pr-str data))
                                                                     explanation)))
        (let [{:event/keys [id type]} data]
          (log/info "Received valid event" "username:" username ", event-id:" id ", type:" (common-events/find-event-or-atomic-op-type data))
          (let [{:event/keys [status]
                 :as         result} (valid-event-handler datascript fluree in-memory? server-password channel username data)]
            (log/debug "username:" username ", event-id:" id ", processed with status:" status)
            ;; forward to everyone if accepted
            (when (and (= :accepted status)
                       (contains? forwardable-events type))
              (log/debug "Forwarding accepted event, event-id:" (pr-str id))
              (clients/broadcast! data))
            ;; acknowledge
            (clients/send! channel result)))))))


(defn- make-websocket-handler
  [datascript fluree in-memory? server-password]
  (fn websocket-handler
    [request]
    (http/as-channel request
                     {:on-close   close-handler
                      :on-receive (make-receive-handler datascript fluree in-memory? server-password)})))


(defn- make-ws-route
  [datascript fluree in-memory? server-password]
  (compojure/routes
    (compojure/GET "/ws" []
                   (make-websocket-handler datascript fluree in-memory? server-password))))


(compojure/defroutes health-check-route
                     (compojure/GET "/health-check" [] "ok"))


(defn make-handler
  [datascript fluree in-memory? server-password]
  (compojure/routes health-check-route
                    (make-ws-route datascript fluree in-memory? server-password)))


(defrecord WebServer
  [config httpkit datascript fluree]

  component/Lifecycle

  (start
    [component]
    (if httpkit
      (do
        (log/warn "Server already started, it's ok. Though it means we're not managing it properly.")
        component)
      (let [{http-conf       :http
             server-password :password
             in-memory?      :in-memory?}
            (:config config)]
        (log/info "Starting WebServer with config:" (pr-str http-conf)
                  "in-memory?" (pr-str in-memory?)
                  "password?" (boolean server-password))
        (assoc component :httpkit
               (http/run-server (make-handler datascript fluree in-memory? server-password) http-conf)))))


  (stop
    [component]
    (log/info "Stopping WebServer")
    (when httpkit
      (httpkit :timeout 100)
      (assoc component :httpkit nil))))


(defn new-web-server
  []
  (map->WebServer {}))

