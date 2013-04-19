(ns pallet.crate.collectd-test
  (:require
   [clojure.test :refer :all]
   [pallet.crate.collectd :as collectd]
   [pallet.actions :refer [package-manager]]
   [pallet.algo.fsmop :refer [complete?]]
   [pallet.api :refer [lift plan-fn group-spec server-spec]]
   [pallet.crate.automated-admin-user :refer [automated-admin-user]]
   [pallet.live-test :refer [images test-nodes]]))

(deftest collectd-config-test
  (is (= '[[PIDFile "/pid"]
           [Plugin syslog [[LogLevel info]]]
           [Plugin cpu []]]
         (collectd/config
          [PIDFile "/pid"]
          (Plugin syslog
                  [[LogLevel info]])
          (Plugin cpu [])))))

(deftest add-load-plugin-test
  (is (= '[[LoadPlugin x] [Plugin "x" []]]
         (collectd/add-load-plugin '[[Plugin x []]]))))

(deftest format-element-test
  (testing "non-blocks"
    (is (= "Collect \"a\" \"b\" \"c\"\n"
           (collectd/format-element 0 ['Collect "a" "b" "c"])))
    (is (= "Collect \"a\"\n" (collectd/format-element 0 ['Collect "a"])))
    (is (= "    Collect \"a\"\n" (collectd/format-element 2 '[Collect "a"])))
    (is (= "Collect \"a\" \"b\"\n" (collectd/format-element 0 '[Collect "a" "b"]))))
  (testing "blocks"
    (is (nil? (collectd/format-element 0 ['Server []])))
    (is (nil? (collectd/format-element 0 ['Server "a" []])))
    (is (= "    <Server>\n      Collect \"a\"\n    </Server>\n"
           (collectd/format-element 2 '[Server [[Collect "a"]]])))
    (is (= "<Server \"s\">\n  Collect \"a\" \"b\"\n</Server>\n"
           (collectd/format-element 0 '[Server "s" [[Collect "a" "b"]]])))))

(deftest format-config-test
  (is (= "PIDFile \"/pid\"\n"
         (collectd/format-config '[[PIDFile "/pid"]])))
  (is (= (str "PIDFile \"/pid\"\n"
              "LoadPlugin \"syslog\"\n"
              "LoadPlugin \"cpu\"\n"
              "<Plugin \"syslog\">\n"
              "  LogLevel info\n"
              "</Plugin>\n")
         (collectd/format-config
          (collectd/add-load-plugin
           '[[PIDFile "/pid"]
             [Plugin "syslog" [[LogLevel info]]]
             [Plugin "cpu" []]]))))
  (is (= (str
          "PIDFile \"/pid\"\n"
          "LoadPlugin \"syslog\"\n"
          "LoadPlugin \"cpu\"\n"
          "LoadPlugin \"memory\"\n"
          "<Plugin \"syslog\">\n"
          "  LogLevel info\n"
          "</Plugin>\n")
         (collectd/format-config
          (collectd/add-load-plugin
           (concat
            (collectd/config
             [PIDFile "/pid"]
             [Plugin "syslog" [[LogLevel info]]]
             [Plugin "cpu" []])
            (collectd/config
             [Plugin "memory" []])))))))

(deftest plugin-config-test
  (is (= [[:MBean "pfx-gc"
           [[:ObjectName "java.lang:type=GarbageCollector,name=*"]
            [:InstancePrefix "pfx.gc"]
            [:Value [[:Type "gauge"] [:Attribute "CollectionTime"]]]]]]
         (collectd/jmx-mbeans "pfx" [:gc])))
  (is (= [:Plugin "org.collectd.java.GenericJMX"
          [[:MBean "pfx-gc"
            [[:ObjectName "java.lang:type=GarbageCollector,name=*"]
             [:InstancePrefix "pfx.gc"]
             [:Value [[:Type "gauge"] [:Attribute "CollectionTime"]]]]]
           [[:Connection
             [[:Host "host"] [:ServiceURL "http://somewhere"]]]]]]
         (collectd/plugin-config
          :generic-jmx
          {:mbeans (collectd/jmx-mbeans "pfx" [:gc])
           :connections [(collectd/plugin-config
                          :generic-jmx-connection
                          {:url "http://somewhere" :host "host"})]}))))

(deftest ^:live-test live-test
  (let [settings {:install-strategy :collectd5-ppa}]
    (doseq [image (images)]
      (test-nodes
       [compute node-map node-types [:install :configure]]
       {:collectd
        (group-spec
         "collectd"
         :image image
         :count 1
         :extends [(collectd/server-spec settings)]
         :phases {:bootstrap (plan-fn (automated-admin-user)
                                      (package-manager :update))
                  :install (plan-fn (package-manager :update))})}))))

(def collectd-test-spec
  (group-spec "collectd"
    :extends [(collectd/server-spec {})]
    :phases {:bootstrap (plan-fn
                          (automated-admin-user)
                          (package-manager :update))}
    :roles #{:live-test}))
