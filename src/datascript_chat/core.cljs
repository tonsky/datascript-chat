(ns datascript-chat.core
  (:require
    [clojure.string :as str]
    [cljs.core.async :as async]
    [datascript.core :as d]
    [datascript-chat.server :as server]
    [datascript-chat.ui :as ui]
    [datascript-chat.util :as u])
  (:require-macros
    [datascript-chat.core :refer [go-loop-sub]]
    [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(def ^:dynamic *room-msg-limit* 30)

;; DATABASE

;; schema is required only for :db.type/ref and :db.cardinality/many
;; other attributes listed just for reference
(def conn (d/create-conn {
  :room/title     {}
  :message/room   {:db/valueType :db.type/ref}
  :message/text   {}
  :message/author {:db/valueType :db.type/ref}
  :message/timestamp {}
  :message/unread {}
  :user/name      {}
  :user/avatar    {}
  :user/me        {}
  :user/state     {}
}))

;; EVENT BUS

(def event-bus (async/chan))
(def event-bus-pub (async/pub event-bus first))

;; ON PAGE LOAD

(defn ^:export start []
  (ui/mount conn event-bus)
  
  ;; initial rooms list population
  (server/call server/get-rooms []
    (fn [rooms]
      (d/transact! conn rooms)
      (async/put! event-bus [:select-room (:db/id (first rooms))])))
  
  ;; initial logged in user population
  (server/call server/whoami []
    (fn [user]
      (d/transact! conn [(assoc user
                           :user/me true
                           :user/state :loaded)])))
  
  ;; subscription to server messages push
  (server/subscribe
    (fn [message]
      (async/put! event-bus [:recv-msg message]))))

;; when logged in user sends message from a page
(go-loop-sub event-bus-pub :send-msg [_ text]
  (when-not (str/blank? text)
    (let [db  @conn
          msg { :message/room   (u/q1-by db :room/selected)
                :message/author (u/q1-by db :user/me)
                :message/text   text }]
      (server/send msg))))

;; when message is pushed from server
(go-loop-sub event-bus-pub :recv-msg [_ msg]
  (let [room (u/q1-by @conn :room/selected)
        msg  (if (== (:message/room msg) room)
               (dissoc msg :message/unread)
               msg)]
    (d/transact! conn [msg])))

(defn- user-stub [uid]
  { :db/id       uid
    :user/name   "Loading..."
    :user/avatar "web/avatars/loading.jpg"
    :user/state :loading })

(defn- load-user [uid]
  (server/call server/get-user [uid]
    (fn [user]
      (d/transact! conn [(assoc user
                           :user/state :loaded)]))))

;; async user data load from server
;; initiated on each message if user is not yet loaded
(let [ch (async/chan)]
  (async/sub event-bus-pub :recv-msg ch)
  (go-loop [loaded-users #{}]
    (let [[_ msg] (<! ch)
          uid     (:message/author msg)]
      (if (contains? loaded-users uid)
        (recur loaded-users)
        (do
          (when-not (d/q '[:find ?e .
                           :in $ ?e
                           :where [?e :user/state :loaded]] @conn uid)
            (d/transact! conn [(user-stub uid)])
            (load-user uid))
          (recur (conj loaded-users uid)))))))

(defn- select-room [db room-id]
  (let [selected (d/q '[:find ?r .
                        :where [?r :room/selected true]] db)]
    (case selected
      nil
        [[:db/add room-id :room/selected true]]
      room-id
        []
      [[:db/retract selected :room/selected true]
       [:db/add room-id :room/selected true]])))

;; when selecting room in UI
(go-loop-sub event-bus-pub :select-room [_ room-id]
  (d/transact! conn [
    [:db.fn/call select-room room-id]
  ]))


(defn- mark-read [db room-id]
  (let [unread (d/q '[:find [?m ...]
                      :in $ ?r
                      :where [?m :message/unread]
                      [?m :message/room ?r]]
                    db room-id)]
    (map (fn [mid] [:db/retract mid :message/unread true]) unread)))

;; when room is selected, mark all messages as read
(go-loop-sub event-bus-pub :select-room [_ room-id]
  (d/transact! conn [
    [:db.fn/call mark-read room-id]
  ]))


;; Clean-up: keeping last N messages per room
(go-loop-sub event-bus-pub :recv-msg [_ msg]
  (let [db @conn
        room-id     (:message/room msg)
        ;; Last ?lim messages
        keep-msgs   (->> (d/q '[:find (max ?lim ?m) .
                                :in $ ?room-id ?lim
                                :where [?m :message/room ?room-id]]
                              db
                              room-id
                              *room-msg-limit*)
                         set)
        ;; All other messages in same room
        remove-msgs (d/q '[:find [?m ...]
                           :in $ ?room-id ?remove-pred 
                           :where [?m :message/room ?room-id]
                           [(?remove-pred ?m)]] ;; filter by custom predicate
                         db
                         room-id
                         #(not (contains? keep-msgs %)))]
    (d/transact! conn
      (map #(vector :db.fn/retractEntity %) remove-msgs))))
