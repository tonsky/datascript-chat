(ns datascript-chat
  (:require
    [clojure.string :as str]
    [cljs.core.async :as async]
    [datascript :as d]
    [datascript-chat.server :as server]
    [datascript-chat.ui :as ui]
    [datascript-chat.util :as u])
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

;; DATABASE

;; :room/title
;; :message/room
;; :message/text
;; :message/author
;; :message/timestamp
;; :message/unread
;; :user/name
;; :user/avatar
;; :user/me
;; :system/state

(def conn (d/create-conn {:message/room   {:db/valueType :db.type/ref}
                          :message/author {:db/valueType :db.type/ref}}))

(reset! ui/conn conn)

(def event-bus (async/chan))
(def event-bus-pub (async/pub event-bus first))

(defn ^:export start []
  (ui/request-rerender @conn event-bus)
  (d/listen! conn
    (fn [tx-report]
      (ui/request-rerender (:db-after tx-report) event-bus)))
  (server/call server/get-rooms []
    (fn [rooms]
      (d/transact! conn rooms)
      (async/put! event-bus [:select-room (:db/id (first rooms))])))
  (server/call server/whoami []
    (fn [user]
      (d/transact! conn [(assoc user :user/me true :system/state :loaded)])))
  (server/subscribe
    (fn [message]
      (async/put! event-bus [:recv-msg message]))))

(let [ch (async/chan)]
  (async/sub event-bus-pub :send-msg ch)
  (go-loop []
    (let [[_ text] (<! ch)
          db  @conn
          msg { :message/room   (u/q1-by db :room/selected)
                :message/author (u/q1-by db :user/me)
                :message/text   text }]
      (server/send msg))
    (recur)))

(let [ch (async/chan)]
  (async/sub event-bus-pub :recv-msg ch)
  (go-loop []
    (let [[_ msg] (<! ch)
          room    (u/q1-by @conn :room/selected)
          msg     (cond-> msg
                    (== (:message/room msg) room)
                      (dissoc :message/unread))]
      (d/transact! conn [msg]))
    (recur)))

(let [ch (async/chan)]
  (async/sub event-bus-pub :recv-msg ch)
  (go-loop [loaded-users #{}]
    (let [[_ msg] (<! ch)
          uid     (:message/author msg)]
      (if (contains? loaded-users uid)
        (recur loaded-users)
        (do
          (when-not (u/q1 '[:find ?e :in $ ?e :where [?e :system/state :loaded]] @conn uid)
            (d/transact! conn [ {:db/id       uid
                                 :user/name   "Loading..."
                                 :user/avatar "avatars/loading.jpg"
                                 :system/state :loading} ])
            (server/call server/get-user [uid]
              (fn [user]
                (d/transact! conn [(assoc user :system/state :loaded)]))))
          (recur (conj loaded-users uid)))))))

(defn- -select-room [db rid]
  (let [selected (u/q1  '[:find ?r :where [?r :room/selected true]] db)
        unread   (u/q1s '[:find ?m :in $ ?r :where [?m :message/unread] [?m :message/room ?r]] db rid)]
    (concat
      (condp = selected
        nil
          [[:db/add rid :room/selected true]]
        rid
          []
        [[:db/retract selected :room/selected true]
         [:db/add rid :room/selected true]])
      (map #(vector :db/retract % :message/unread true) unread))))

(let [ch (async/chan)]
  (async/sub event-bus-pub :select-room ch)
  (go-loop []
    (let [[_ rid] (<! ch)]
      (d/transact! conn [[:db.fn/call -select-room rid]]))
    (recur)))

