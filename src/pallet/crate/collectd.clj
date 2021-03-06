;;; Copyright 2012, 2013 Hugo Duncan.
;;; All rights reserved.

(ns pallet.crate.collectd
  "A pallet crate to install and configure collectd"
  (:require [clojure.string :refer [join split]]
            [clojure.tools.logging :refer [debugf]]
            [pallet.action :refer [with-action-options]]
            [pallet.actions
    :refer [directory exec-checked-script exec-script packages
           remote-directory remote-file service symbolic-link user group
           assoc-settings update-settings service-script]
    :rename {user user-action group group-action
             assoc-settings assoc-settings-action
             service service-action
             service-script service-script-action}]
            [pallet.api :refer [plan-fn] :as api]
            [pallet.crate-install :as crate-install]
            [pallet.crate
    :refer [defplan assoc-settings defmethod-plan get-settings
            get-node-settings group-name nodes-with-role target-id]]
            [pallet.crate.service
             :refer [supervisor-config supervisor-config-map] :as service]
            [pallet.script.lib :refer [config-root file log-root pid-root user-home]]
            [pallet.stevedore :refer [fragment script]]
            [pallet.utils :refer [apply-map]]
            [pallet.version-dispatch
             :refer [defmulti-version-plan defmethod-version-plan]]))


(def ^{:doc "Flag for recognising changes to configuration"}
  config-changed-flag "collectd-config")

(def facility ::collectd)

;;; # Settings
(defn default-settings []
  {:version "5.1.0"
   :dist-url "http://collectd.org/files/"
   :user "collectd"
   :owner "collectd"
   :group "collectd"
   :src-dir "/opt/collectd"
   :prefix "/usr/local"
   :config-dir "/etc"
   :plugin-dir "/var/lib/collectd"
   :log-dir "/var/log"
   :bin "/usr/sbin/collectd"
   :service-name "collectd"
   :supervisor :nohup
   :config [;; [:PIDFile (script (str (~pid-root) "/collectd.pid"))]
            ]
   :service "collectd"})

(defn source-url [{:keys [version dist-url]}]
  (format "%s/collectd-%s.tar.gz" dist-url version))

(defn run-command
  "Return a script command to run riemann."
  [{:keys [bin home user config-dir] :as settings}]
  (fragment ((file ~bin) -f -C (file ~config-dir "collectd.conf"))))

;;; At the moment we just have a single implementation of settings,
;;; but this is open-coded.
(defmulti-version-plan settings-map [version settings])

(defmethod-version-plan
    settings-map {:os :linux}
    [os os-version version settings]
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
             :packages ["collectd"]))))

(defplan settings
  "Settings for collectd"
  [{:keys [user owner group dist-url version config install-strategy
           instance-id]
    :as settings}]
  (let [settings (update-in (merge (default-settings) (dissoc settings :config))
                            [:config] concat (:config settings))
        settings (settings-map (:version settings) settings)
        settings (update-in settings [:run-command]
                            #(or % (run-command settings)))]
    (debugf "collectd settings %s" settings)
    (assoc-settings facility settings {:instance-id instance-id})
    (supervisor-config
     facility settings (select-keys settings [:instance-id]))))

(defplan add-config
  "Add configuration for collectd. The given `config` is concatenated onto the
   collectd configuration. This can be used to allow other crates to contribute
   to the collectd configuration."
  [config & {:keys [instance-id] :as options}]
  (update-settings facility {:instance-id instance-id}
                   update-in [:config] concat config))

(defplan add-plugin-config
  "Add configuration for a collectd `plugin`. The `plugin` has to be a valid
   dispatch value for `plugin-config`.  The given `config` is concatenated onto
   the collectd plugin configuration. This can be used to allow other crates to
   contribute to a collectd plugin configuration."
  [plugin config & {:keys [instance-id] :as options}]
  (update-settings facility {:instance-id instance-id}
                   update-in [:plugins plugin] concat config))

;;; # Install
(defmulti feature
  "Provide compile time information for enabling collectd features.
   Each feature should return a map with :packages and :configure keys.
   :packages should list the required system packages, and :configure
   should return a string to pass to the configure script.
   This is a multimethod so that it can be extended externally."
  (fn [feature] feature))

(defplan link-libjvm-so
  [{:keys [prefix] :as settings}]
  (symbolic-link
   (script (str @JAVA_HOME "/jre/lib/amd64/server/libjvm.so"))
   "/usr/local/lib/libjvm.so")
  (exec-checked-script
   "ldconfig for libjvm"
   ("ldconfig")))

(defmethod feature :java
  [_]
  {:configure "--with-java=yes"
   :configure-env (array-map
                   :JAVA_CPPFLAGS "-I$JAVA_HOME/include"
                   :JAVA_LDFLAGS "-L$JAVA_HOME/jre/lib/amd64/server"
                   ;; :JAVA_HOME ""
                   )       ; to prevent rpath in resulting java.so
   :install link-libjvm-so})

(defmethod-plan crate-install/install ::source
  [facility instance-id]
  (let [{:keys [owner group src-dir prefix url features pkgs] :as settings}
        (get-settings facility {:instance-id instance-id})
        pkgs (concat pkgs
                     (mapcat (comp :packages feature) features))
        config (join " "
                     (map (comp :configure feature) features))]
    (doseq [f (filter identity (map (comp :install feature) features))]
      (f settings))
    (packages
     :centos (concat pkgs ["gcc" "make"])
     :apt (concat pkgs ["build-essential" "libsensors-dev" "libsnmp-dev"])
     :aptitude (concat pkgs ["build-essential"]))
    (apply pallet.actions/remote-directory src-dir
           (apply concat (merge {:owner owner :group group}
                                (:remote-directory settings))))
    (with-action-options {:script-dir src-dir :sudo-user owner}
      (exec-checked-script
       "Build collectd"
       (~(join " " (map
                    (fn [[k v]] (str (name k) "=\"" v \"))
                    (mapcat (comp :configure-env feature) features)))
        "./configure" "--prefix" ~prefix ~config)
       ("make all")))
    (with-action-options {:script-dir src-dir}
      (exec-checked-script
       "Install collectd"
       ("make install")))))

(defplan install
  "Install collectd."
  [{:keys [instance-id]}]
  (let [settings (get-settings facility {:instance-id instance-id})]
    (crate-install/install facility instance-id)))

;;; # User
(defplan user
  "Create the collectd user"
  [{:keys [instance-id] :as options}]
  (let [{:keys [user owner group]} (get-settings facility options)]
    (group-action group :system true)
    (when (not= owner user)
      (user-action owner :group group :system true))
    (user-action
     user :group group :system true :create-home true :shell :bash)))

;;; # Configuration

;;; Directives are configured with a sequence of nested sequences.  The nested
;;; sequences representing attributes are sequences of a name followed by
;;; scalars.  Blocks are represented as sequences containing a final sequence
;;; value.

(defmacro config
  "Returns a collectd configuration literal. Note that values are not escaped
   here, so this can only be used for literal content."
  [& args]
  `'[~@args])

(defmulti format-value type)

(defmethod format-value :default
  [v] (pr-str v))

(defmethod format-value clojure.lang.Named
  [v] (name v))

(defn config-block
  [[_ & [n v]]]
  (or (and (sequential? v) v)
      (and (sequential? n) n)
      nil))

(defn format-element
  [offset [element-name & [n v :as values] :as stmt]]
  (let [element-name (format-value element-name)
        block (config-block stmt)
        n (if (and block (sequential? n)) nil n)
        prefix (apply str (repeat offset "  "))]
    (debugf "format-element %s %s %s" element-name n (doall block))
    (if block
      (when (seq block)
        (debugf "format-element block %s" (ffirst block))
        ;; (assert
        ;;  (every? vector? block)
        ;;  "A collectd block must be composed of a vector of vector elements")
        (str
         prefix "<" element-name (if n (str " " (pr-str (name n))) "") ">\n"
         (join (map (partial format-element (inc offset)) block))
         prefix "</" element-name ">\n"))
      (str prefix element-name " "
           (join " " (map format-value values)) \newline))))

(defn format-config
  [config]
  (debugf "collectd/format-config %s" (vec config))
  (join (map (partial format-element 0) config)))

(defn add-load-plugin
  "Looks for Plugin configuration blocks, and adds LoadPlugin blocks for them."
  [config]
  (let [g (group-by
           (comp boolean config-block)
           (concat [[]] config))
        globals (get g false)
        other (get g true)]
    (debugf
     "add-load-plugin %s %s %s"
     (vec config) (doall globals) (doall other))
    (concat
     (filter seq globals)
     (->> config
          (filter #(= "Plugin" (name (first %))))
          (map second)
          (map #(vector 'LoadPlugin %))
          (distinct))
     ;; convert plugin names to be unqualified (LoadPlugin requires qualified
     ;; names)
     (map
      (fn [x]
        (if (and (sequential? x) (= "Plugin" (name (first x))))
          (update-in x [1] #(last (split (name %) #"\.")))
          x))
      other))))


(defplan config-file
  "Helper to write config files"
  [filename file-source options]
  (let [{:keys [owner group config-dir]} (get-settings facility options)]
    (apply
     remote-file (str config-dir "/" filename)
     :flag-on-changed config-changed-flag
     :owner owner :group group
     (apply concat file-source))))

(defn plugin-config-from-settings
  "Calculates a plugin's config from the :plugins in the facility settings."
  [plugins]
  (debugf "plugin-config-from-settings %s" (vec plugins))
  (reduce
   (fn [config [plugin plugin-config]]
     (conj config `[:Plugin ~plugin ~(add-load-plugin plugin-config)]))
   []
   plugins))

(defplan config-from-settings
  "Calculate the contents of the collectd conf file from the settings"
  [{:keys [instance-id] :as options}]
  (let [{:keys [config plugins] :as settings} (get-settings facility options)]
    (add-load-plugin (concat config (plugin-config-from-settings plugins)))))

(defplan configure
  "Write the collectd conf file"
  [{:keys [instance-id] :as options}]
  (let [config (config-from-settings options)]
    (config-file
     "collectd.conf" {:content (format-config config) :literal true}
     options)))


(defmethod supervisor-config-map [facility :runit]
  [_ {:keys [run-command service-name user] :as settings} options]
  {:service-name service-name
   :run-file {:content (str "#!/bin/sh\nexec " run-command)}})

(defmethod supervisor-config-map [facility :upstart]
  [_ {:keys [run-command service-name user] :as settings} options]
  {:service-name service-name
   :exec run-command
   :setuid user})

(defmethod supervisor-config-map [facility :nohup]
  [_ {:keys [run-command service-name user] :as settings} options]
  {:service-name service-name
   :run-file {:content run-command}
   :user user})

(defmulti service-script-content
  (fn [{:keys [service-impl] :as settings}] (or service-impl :upstart)))

(defmethod service-script-content :upstart
  [{:keys [config-dir prefix] :or {prefix "/usr"} :as settings}]
  {:content (str
             "start on runlevel [2345]
stop on runlevel [S016]
respawn
expect fork
respawn limit 10 5
pre-start exec " prefix "/sbin/collectd -t -C " config-dir "/collectd.conf
exec " prefix "/sbin/collectd -C " config-dir "/collectd.conf")
   :literal true})

(defplan service-script
  "Install the collectd service script.

   Specify `:if-config-changed true` to make actions conditional on a change in
   configuration.

   Other options are as for `pallet.action.service/service`. The service
   name is looked up in the request parameters."
  [{:keys [action if-config-changed if-flag instance-id] :as options}]
  (let [{:keys [service service-impl] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (apply-map
     service-script service
     (merge {:service-impl (or service-impl :upstart)}
            (service-script-content settings)
            options))))

(defplan service
  "Run the collectd service."
  [{:keys [action if-flag if-stopped if-config-changed instance-id]
    :or {action :manage}
    :as options}]
  (let [options (-> options
                    (dissoc :instance-id)
                    (cond-> if-config-changed
                            (assoc :if-flag config-changed-flag)))
        {:keys [supervision-options] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (service/service settings (merge supervision-options options))))

(defplan ensure-service
  "Ensure the service is running and has read the latest configuration."
  [& {:keys [instance-id] :as options}]
  (service {:instance-id instance-id
            :if-stopped true
            :action :start})
  (service {:instance-id instance-id
            :if-flag config-changed-flag
            :action :reload}))

(defn server-spec
  "Returns a server-spec that installs and configures collectd."
  [settings & {:keys [instance-id] :as options}]
  (api/server-spec
   :phases
   {:settings (plan-fn
               (pallet.crate.collectd/settings (merge settings options)))
    :install (plan-fn
              (user options)
              (install options))
    :configure (plan-fn
                (configure options)
                (service (merge options {:action :enable})))}
   :default-phases [:settings :install :configure]))

;;; # Configuration generating functions

;;; ## Plugin configuration
(defmulti plugin-config (fn [plugin options] plugin))

(defmethod plugin-config :logfile
  [_ {:keys [log-level log-dir] :or {log-level 'info}}]
  [:Plugin :logfile
   [[:LogLevel log-level]
    [:File (str log-dir "/collectd.log")]]])

(defmethod plugin-config :write_graphite
  [_ {:keys [host port prefix] :or {port 2003 prefix "collectd."}}]
  (assert host "Must specify a host for write_graphite configuration")
  [:Plugin :write_graphite
   [[:Carbon [[:Host host] [:Port port] [:Prefix prefix]]]]])

(defmethod plugin-config :java
  [_ {:keys [jvm-args plugins]}]
  `[:Plugin :java
   ~@(map #(vector :JVMArg %) jvm-args)
   ~@plugins])

(defmethod plugin-config :generic-jmx
  [_ {:keys [mbeans connections]}]
  `[:Plugin "org.collectd.java.GenericJMX"
    [~@mbeans
     ~@connections]])

(defmethod plugin-config :generic-jmx-connection
  [_ {:keys [url host prefix mbeans]}]
  `[[:Connection
     [~@(when host [[:Host host]])
      [:ServiceURL ~url]
      ~@(when prefix [[:InstancePrefix prefix]])
      ~@(map #(vector :Collect (second %)) mbeans)]]])

;;; ## JMX configuration
(defn mbean-value
  [attribute type & {:keys [prefix]}]
  `[:Value
    [[:Type ~type]
     [:Attribute ~attribute]
     ~@(when prefix [[:InstancePrefix prefix]])]])

(defn mbean-table
  [attribute type & {:keys [prefix]}]
  `[:Value
    [[:Type ~type]
     [:Table true]
     [:Attribute ~attribute]
     ~@(when prefix [[:InstancePrefix prefix]])]])

(defn mbean [stat-name object-name {:keys [prefix from]} & values]
  `[:MBean ~stat-name
    [[:ObjectName ~object-name]
     ~@(when prefix [[:InstancePrefix prefix]])
     ~@values]])

(defn jmx-mbeans
  "Return the collectd spec for specified beans, named with a given `prefix`.
   Known bean components are :os, :memory, :memory-pool, :gc, :runtime,
   :threading, :compilation and :class-loading."

  [prefix components]
  (map
   {:os
    (mbean
     (str prefix "-os") "java.lang:type=OperatingSystem"
     {:prefix (str prefix ".os")}
     (mbean-value "OpenFileDescriptorCount" "gauge" :prefix "filedes.open")
     (mbean-value "MaxFileDescriptorCount" "gauge" :prefix "filedes.max")
     (mbean-value "CommittedVirtualMemorySize" "memory" :prefix "memcommit")
     (mbean-value "FreePhysicalMemorySize" "memory" :prefix "memphy.free")
     (mbean-value "TotalPhysicalMemorySize" "memory" :prefix "memphy.total")
     (mbean-value "FreeSwapSpaceSize" "memory" :prefix "swap.free")
     (mbean-value "TotalSwapSpaceSize" "memory" :prefix "swap.total")
     (mbean-value "ProcessCpuTime" "gauge" :prefix "cpu.time")
     (mbean-value "SystemLoadAverage" "gauge" :prefix "loadavg"))

    :memory
    (mbean
     (str prefix "-memory") "java.lang:type=Memory"
     {:prefix (str prefix ".memory")}
     (mbean-table "HeapMemoryUsage" "memory")
     (mbean-table "NonHeapMemoryUsage" "memory")
     (mbean-value "ObjectPendingFinalizationCount" "gauge"
                  :prefix "pending"))

    :memory-pool
    (mbean
     (str prefix "-mempool") "java.lang:type=MemoryPool,name=*"
     {:prefix (str prefix ".mempool") :from "prefix"}
     (mbean-table "Usage" "memory" :prefix "usage")
     (mbean-table "PeakUsage" "memory" :prefix "peakusage"))

    :gc
    (mbean
     (str prefix "-gc") "java.lang:type=GarbageCollector,name=*"
     {:prefix (str prefix ".gc") :from "prefix"}
     (mbean-value "CollectionTime" "gauge"))

    :threading
    (mbean
     (str prefix "-threading") "java.lang:type=Threading"
     {:prefix (str prefix ".threading")}
     (mbean-value "ThreadCount" "gauge" :prefix "threads.count")
     (mbean-value "DaemonThreadCount" "gauge" :prefix "threads.daemon")
     (mbean-value "TotalStartedThreadCount" "gauge" :prefix "threads.total")
     (mbean-value "CurrentThreadCpuTime" "derive" :prefix "cpu")
     (mbean-value "CurrentThreadUserTime" "derive" :prefix "user"))

    :runtime
    (mbean
     (str prefix "-runtime") "java.lang:type=Runtime"
     {:prefix (str prefix ".runtime")}
     (mbean-value "StartTime" "gauge" :prefix "starttime")
     (mbean-value "Uptime" "gauge" :prefix "uptime"))

    :compilation
    (mbean
     (str prefix "-compilation") "java.lang:type=Compilation"
     {:prefix (str prefix ".compilation")}
     (mbean-value "TotalCompilationTime" "gauge" :prefix "time"))

    :class-loading
    (mbean
     (str prefix "-classloading") "java.lang:type=ClassLoading"
     {:prefix (str prefix ".classes")}
     (mbean-value "LoadedClassCount" "gauge" :prefix "loaded")
     (mbean-value "UnloadedClassCount" "gauge" :prefix "unloaded")
     (mbean-value "TotalLoadedClassCount" "gauge" :prefix "total"))}
   components))
