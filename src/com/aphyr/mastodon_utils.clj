(ns com.aphyr.mastodon-utils
  (:refer-clojure :exclude [load])
  (:require [cheshire.core :as json]
            [clojure [core :as c]
                     [edn :as edn]
                     [pprint :refer [pprint]]
                     [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools [cli :as cli]
                           [logging :refer [info warn]]]
            [clj-http.client :as http]
            [dom-top.core :refer [loopr with-retry]]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermission)))

(def mastodon-map-path
  "File where we save and load mastodon credentials from."
  (str (System/getenv "HOME") "/.mastodon-utils.edn"))

(defn register-app!
  "Registers a new app on the Mastodon server. Options:

    {:url         The Mastodon server URL
     :client-name The name of your application (ooptional)
     :client-site The URL for your application (optional)}

  Returns the Mastodon map, but now with a :client-id, :client-secret, and
  :vapid-key"
  [{:keys [url client-name client-site] :as mastodon}]
  (let [res (http/post
              (str url "/api/v1/apps")
              {:form-params
               {:client_name (or client-name "com.aphyr.mastodon-utils")
                :website     (or client-site
                                 "https://github.com/aphyr/mastodon-utils")
                :redirect_uris  "urn:ietf:wg:oauth:2.0:oob"
                :scopes         "read write admin:read admin:write"}
               :as :json})
        body (:body res)]
    (assoc mastodon
           :client-id (:client_id body)
           :client-secret (:client_secret body)
           :vapid-key (:vapid_key body))))

(defn auth-code-url
  "Takes a mastodon map and returns the URL you should visit, logged in as an
  admin, to obtain an auth code."
  [{:keys [url client-id] :as mastodon}]
  (str url "/oauth/authorize?response_type=code&client_id=" client-id "&redirect_uri=urn:ietf:wg:oauth:2.0:oob&scope=read+write+admin:read+admin:write"))

(defn access-token!
  "Gets a new access token for a mastodon map (with app info), given an
  authorization code. Returns the mastodon map with :access-token added."
  [{:keys [client-id client-secret url] :as mastodon} auth-code]
  (let [res (http/post
              (str url "/oauth/token")
              {:form-params
               {:client_id     client-id
                :client_secret client-secret
                :code          auth-code
                :redirect_uri  "urn:ietf:wg:oauth:2.0:oob"
                :grant_type    "authorization_code"
                :scope         "write read admin:write admin:read"}
               :as :json})
        body (:body res)]
    (assoc mastodon :access-token (:access_token body))))

(defn save!
  "Saves the mastodon map to disk."
  [m]
  (let [file (io/file mastodon-map-path)]
    (spit file (with-out-str (pprint m)))
    (Files/setPosixFilePermissions (.toPath file)
                                   #{PosixFilePermission/OWNER_WRITE
                                     PosixFilePermission/OWNER_READ})))

(defn load
  "Loads mastodon map from disk."
  []
  (edn/read-string (slurp mastodon-map-path)))

;; Once you're authed, here are API mechanisms

(defn http-opts
  "Constructs an HTTP options map from a mastodon map."
  [m]
  {:as :json
   :headers {:Authorization (str "Bearer " (:access-token m))}})

(defmacro with-backoff
  "Tries to evaluate body with exponential backoff on HTTP 429"
  [& body]
  `(with-retry [timeout# 1000]
     ~@body
     (catch clojure.lang.ExceptionInfo e#
       ;(prn "Caught" (ex-data e#))
       (if (= 429 (:status (ex-data e#)))
         (do (info "Backing off for " timeout# "ms")
             (Thread/sleep timeout#)
             (~'retry (min (* timeout# 2) 64000)))
         (throw e#)))))

(defn paginate
  "Takes a mastodon map and an HTTP response with next pagination links and
  yields a lazy sequence of the body for each page, concatenated together."
  [m res]
  (lazy-seq
    (concat (:body res)
            (when-let [next (:next (:links res))]
              (with-backoff
                (paginate m (http/get (:href next)
                                      (http-opts m))))))))

(defn accounts
  "Returns all local accounts, with lazy pagination. Options are passed as
  query params: see https://docs.joinmastodon.org/methods/admin/accounts/#v2"
  [m opts]
  (with-backoff
    (let [r (http/get (str (:url m) "/api/v2/admin/accounts")
                      (assoc (http-opts m)
                             :query-params opts))]
      (paginate m r))))

(defn followers
  "Returns followers of an account map. https://docs.joinmastodon.org/methods/accounts/#followers"
  ([m account]
   (followers m account {}))
  ([m account opts]
   (with-backoff
     (paginate m (http/get (str (:url m) "/api/v1/accounts/" (:id account)
                                "/followers")
                           (assoc (http-opts m)
                                  :query-params opts))))))

(defn following
  "Returns all accounts an account map follows. https://docs.joinmastodon.org/methods/accounts/#following"
  ([m account]
   (following m account {}))
  ([m account opts]
   (with-backoff
     (paginate m (http/get (str (:url m) "/api/v1/accounts/" (:id account)
                                "/following")
                           (assoc (http-opts m)
                                  :query-params opts))))))
