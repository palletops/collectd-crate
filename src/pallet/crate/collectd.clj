;;; Copyright 2012 Hugo Duncan.
;;; All rights reserved.

(ns pallet.crate.collectd
  "A pallet crate to install and configure collectd"
  (:use
   [clojure.string :only [join]]
   [clojure.algo.monads :only [m-when]]
   [pallet.action :only [with-action-options]]
   [pallet.actions
    :only [directory exec-checked-script exec-script packages
           remote-directory remote-file service symbolic-link user group
           assoc-settings]
    :rename {user user-action group group-action
             assoc-settings assoc-settings-action
             service service-action}]
   [pallet.api :only [plan-fn server-spec]]
   [pallet.crate
    :only [def-plan-fn assoc-settings defmethod-plan get-settings
           get-node-settings group-name nodes-with-role target-id]]
   [pallet.crate-install :only [install]]
   [pallet.script.lib :only [pid-root log-root config-root user-home]]
   [pallet.stevedore :only [script]]
   [pallet.utils :only [apply-map]]
   [pallet.version-dispatch
    :only [defmulti-version-plan defmethod-version-plan]]))


(def ^{:doc "Flag for recognising changes to configuration"}
  collectd-config-changed-flag "collectd-config")

;;; # Configuration DSL
(defn config-block
  [[block-name & values]]
  (let [filter-fn (fn [form] (and (list? form)
                                  (<= (int \A)
                                      ((comp int first name first) form)
                                      (int \Z))))
        forms (filter filter-fn values)
        kw-vals (remove filter-fn values)
        [kw-vals n] (if (even? (count kw-vals))
                      [kw-vals nil]
                      [(rest kw-vals) (first kw-vals)])]
    `(vec (concat
           [::config-block '~block-name '~n]
           ~(vec (map config-block forms))
           ~(vec (map
                  #(vector ::config (list 'quote (first %)) (second %))
                  (partition 2 kw-vals)))))))

(defmacro collectd-config
  "Define a scope for generating plugin config blocks"
  [& body]
  (config-block `(implicit ~@body)))

(defmacro collectd-plugin-config
  "Define a scope for generating plugin config blocks"
  [plugin-name & body]
  (config-block `(~'Plugin ~plugin-name ~@body)))

;;; # Settings
(defn default-settings []
  {:version "5.1.0"
   :dist-url "http://collectd.org/files/"
   :user "collectd"
   :owner "collectd"
   :group "collectd"
   :src-dir "/usr/local/collectd"
   :config-dir "/etc/collectd"
   :plugin-dir "/var/lib/collectd"
   :config (collectd-config
            PIDFile (script (str (~pid-root) "/collectd.pid")))
   :service "collectd"})

(defn source-url [{:keys [version dist-url]}]
  (format "%s/collectd-%s.tar.gz" dist-url version))

;;; At the moment we just have a single implementation of settings,
;;; but this is open-coded.
(defmulti-version-plan settings-map [version settings])

(defmethod-version-plan
  settings-map {:os :linux}
  [os os-version version settings]
  (m-result
   (cond
    (= ::source (:install-strategy settings))
    (if (:remote-directory settings)
      settings
      (assoc settings
        :remote-directory {:url (source-url settings)}))

    (= :collectd5-ppa (:install-strategy settings))
    (-> settings
        (update-in
         [:packages]
         #(or % ["collectd"]))
        (update-in
         [:package-source :aptitude]
         #(or % {:url "ppa:joey-imbasciano/collectd5"}))
        (update-in
         [:package-source :name]
         #(or % "joey-imbasciano-collectd5"))
        (assoc :config-dir "/etc")
        (assoc :install-strategy :package-source))

    (:install-strategy settings) settings
    (:remote-directory settings) (assoc settings :install-strategy ::source)

    :else (let [url (source-url settings)]
            (assoc settings
              :install-strategy :packages
              :packages ["collectd"])))))

(def-plan-fn collectd-settings
  "Settings for collectd"
  [{:keys [user owner group dist-url version config install-strategy
           instance-id]
    :as settings}]
  [settings (m-result
             (update-in (merge (default-settings) (dissoc settings :config))
                        [:config] (comp vec concat)
                        (drop 3 (:config settings))))
   settings (settings-map (:version settings) settings)]
  (assoc-settings :collectd settings {:instance-id instance-id}))

;;; # Install
(defmethod-plan install ::source
  [facility instance-id]
  [{:keys [owner group src-dir home url] :as settings}
   (get-settings facility {:instance-id instance-id})]
  (packages
   :centos ["gcc" "make"]
   :apt ["build-essential" "libsensors-dev" "libsnmp-dev"]
   :aptitude ["build-essential"])
  (apply pallet.actions/remote-directory src-dir
         (apply concat (merge {:owner owner :group group}
                              (:remote-directory settings))))
  (with-action-options {:script-dir src-dir :sudo-user owner}
    (exec-checked-script
     "Build collectd"
     ("./configure" "--prefix" ~home)
     ("make all")))
  (with-action-options {:script-dir src-dir}
    (exec-checked-script
     "Install collectd"
     ("make install"))))

(def-plan-fn install-collectd
  "Install collectd."
  [& {:keys [instance-id]}]
  [settings (get-settings :collectd {:instance-id instance-id})]
  (install :collectd instance-id))

;;; # User
(def-plan-fn collectd-user
  "Create the collectd user"
  [{:keys [instance-id] :as options}]
  [{:keys [user owner group home]} (get-settings :collectd options)]
  (group-action group :system true)
  (m-when (not= owner user) (user-action owner :group group :system true))
  (user-action user :group group :system true :create-home true :shell :bash))

;;; # Configuration
(def-plan-fn config-file
  "Helper to write config files"
  [{:keys [owner group config-dir] :as settings} filename file-source]
  (apply
   remote-file (str config-dir "/" filename)
   :flag-on-changed collectd-config-changed-flag
   :owner owner :group group
   (apply concat file-source)))

(defmulti format-scoped-blocks
  (fn [config] (if (vector? config)
                 (or
                  (and (= ::config-block (first config))
                       (= `implicit (second config))
                       ::top-level)
                  (#{::config-block ::config} (first config))
                  ::vector)
                 (type config))))

(defmethod format-scoped-blocks :default
  [config]
  (pr-str config))

(defmethod format-scoped-blocks ::top-level
  [[_ block-type n & values]]
  (str
   (join \newline
         (map format-scoped-blocks (filter #(= ::config (first %)) values)))
   \newline
   (join \newline
         (->> values
              (filter
               #(and (= ::config-block (first %)) (= 'Plugin (second %))))
              (map #(vector ::config 'LoadPlugin (nth % 2)))
              (map format-scoped-blocks)))
   \newline
   (join \newline
         (map format-scoped-blocks (remove #(= ::config (first %)) values)))))

(defmethod format-scoped-blocks ::config-block
  [[_ block-type n & values]]
  (when (seq values)
    (str
     "<" block-type (if n (str " " n) "") ">\n"
     (join \newline (map format-scoped-blocks values))
     \newline
     "</" block-type ">\n")))

(defmethod format-scoped-blocks ::config
  [[_ n values]]
  (str n " " (format-scoped-blocks values)))

(defmethod format-scoped-blocks ::vector
  [values]
  (join " " (map format-scoped-blocks values)))

(defn format-config
  [config]
  (format-scoped-blocks config))

(def-plan-fn collectd-conf
  "Helper to write the collectd conf file"
  [{:keys [instance-id] :as options}]
  [{:keys [config] :as settings} (get-settings :collectd options)]
  (config-file
   settings "collectd.conf" {:content (format-config config) :literal true}))

(def-plan-fn collectd-init-service
  "Control the collectd service.

   Specify `:if-config-changed true` to make actions conditional on a change in
   configuration.

   Other options are as for `pallet.action.service/service`. The service
   name is looked up in the request parameters."
  [{:keys [action if-config-changed if-flag instance-id] :as options}]
  [{:keys [service]} (get-settings :collectd {:instance-id instance-id})
   options (m-result (if if-config-changed
                       (assoc options :if-flag collectd-config-changed-flag)
                       options))]
  (apply-map service-action service options))

(defn collectd
  "Returns a server-spec that installs and configures collectd"
  [settings & {:keys [instance-id] :as opts}]
  (server-spec
   :phases
   {:settings (collectd-settings (merge settings opts))
    :install (plan-fn
              (collectd-user opts)
              (install-collectd :instance-id instance-id))
    :configure (plan-fn (collectd-conf opts))}))
