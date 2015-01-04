(ns datascript-chat.ui
  (:require
    [clojure.string :as str]
    [cljs.core.async :as async]
    [datascript :as d]
    [datascript-chat.util :as u]
    [datascript-chat.react :as r :include-macros true]
    [sablono.core :as s]
    [goog.string]
    [goog.string.format] ))

;; UTILS

(defn- format-time [date]
  (goog.string/format "%02d:%02d" (.getHours date) (.getMinutes date)))

(defn- should-scroll? [node]
  (<=
    (- (.-scrollHeight node) (.-scrollTop node) (.-offsetHeight node))
    0))

;; COMMUNICATION

(defn- select-room [chan rid]
  (async/put! chan [:select-room rid]))

(defn- send-msg [chan text]
  (async/put! chan [:send-msg text]))


;; UI COMPONENTS

(r/defc avatar [user]
  [:.message__avatar
    [:img {:src (:user/avatar user "web/avatars/loading.jpg")}]])

(r/defc room [{:keys [room last-msg unread]} event-bus]
  (let [user (:message/author last-msg)]
    [:.topic { :class    (when (:room/selected room) "topic_selected")
               :on-click (fn [_]
                           (select-room event-bus (:db/id room))
                           (.focus (.getElementById js/document "compose__area")))}
      (if last-msg
        (list
          [:img.topic__avatar {:src (:user/avatar user)}]
          [:.topic__title (:room/title room)
            (when (pos? unread)
              [:span.topic__unread (str unread (when (>= unread 30) "+"))])]
          [:.topic__msg (:message/text last-msg)]
          [:.topic__ts
            (:user/name user)
            " at "
            (format-time (:message/timestamp last-msg))
            ])
        [:.topic__title (:room/title room)])]))

(r/defc rooms-pane [rooms event-bus]
  [:#rooms__pane.pane
   [:#rooms__header.header
    [:.title "Rooms"]]
   (map #(room % event-bus) rooms)])

(r/defc text [text]
  [:.message__text
    (map #(vector :p %) (str/split-lines text))])

(r/defc message [msg]
  (let [user (:message/author msg)]
    [:.message {:key   (:db/id msg)
                :class [(when (:message/unread msg) "message_unread")
                        (when (:user/me user)       "me")]}
      (avatar user)
      [:.message__name
        (:user/name user)
        [:span.message__ts
          (format-time (:message/timestamp msg))]]
      (text (:message/text msg))]))

(r/defc chat-pane [{:keys [room-id room-title msgs]}]
  [:#chat__pane.pane
   [:#chat__header.header
    [:.title room-title]]
   (map message msgs)]
  :will-update (fn [node]
                 (r/remember :sticky? (should-scroll? node)))
  :did-update  (fn [node]
                 (when (r/recall :sticky?)
                   (set! (.-scrollTop node) (.-scrollHeight node)))))

(defn- textarea-keydown [callback]
  (fn [e]
    (if (and (== (.-keyCode e) 13) ;; enter
             (not (.-shiftKey e))) ;; no shift
      (do
        (callback (.. e -target -value))
        (set! (.. e -target -value) "")
        (.preventDefault e)))))

(r/defc compose-pane [{:keys [user]} event-bus]
  [:#compose
    (avatar user)
    [:textarea#compose__area.message__text
      { :placeholder "Reply..."
        :auto-focus  true
        :on-key-down  (textarea-keydown #(send-msg event-bus %)) }]])

(r/defc window [{:keys [rooms chat compose]} event-bus]
  [:#window
    [:#rooms (rooms-pane rooms event-bus)]
    [:#chat  (chat-pane chat)]
    (compose-pane compose event-bus)])

;; RENDER MACHINERY

(defn- prepare-ui [db]
  {:rooms (let [rooms (->> (u/qes-by db :room/title)
                           (sort-by :room/title))
                last-msgs (u/qmap '[:find  ?r (max ?m)
                                    :where [?m :message/room ?r]]
                                  db)
                unread-counts (u/qmap '[:find ?r (count ?m)
                                        :where [?m :message/unread]
                                        [?m :message/room ?r]]
                                      db)]
            (for [{rid :db/id :as room} rooms]
              {:room room
               :last-msg (when-let [mid (get last-msgs rid)]
                           (d/entity db mid))
               :unread (get unread-counts rid)}))
   :chat (let [[room-id room-title]
               (->> (d/q '[:find ?r ?t
                           :where [?r :room/selected true]
                           [?r :room/title ?t]] db)
                    first)
               msgs (->> (u/qes-by db :message/room room-id)
                         (sort-by :message/timestamp))]
           {:room-id room-id
            :room-title room-title
            :msgs msgs})
   :compose {:user (u/qe-by db :user/me true)}})


(def ^:dynamic *debug-render* true)

(def render-data (atom nil))

(defn request-rerender [db event-bus]
  (reset! render-data [db event-bus]))

(defn- -render [db event-bus]
  (if *debug-render*
    (let [key (str "Render (" (count (:eavt db)) " datoms)")]
      (.time js/console key)
      (-> db
          (prepare-ui)
          (window event-bus)
          (r/render (.-body js/document)))
      (.timeEnd js/console key))
    (r/render (window db event-bus) (.-body js/document))))

(defn- render []
  (when-let [args @render-data]
    (apply -render args)
    (reset! render-data nil)))

(add-watch render-data :render (fn [_ _ old-val new-val]
  (when (and (nil? old-val) new-val)
    (js/requestAnimationFrame render))))
