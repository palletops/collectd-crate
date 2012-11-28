(ns pallet.crate.collectd-test
  (:use
   clojure.test
   pallet.crate.collectd
   [pallet.actions :only [package-manager]]
   [pallet.algo.fsmop :only [complete?]]
   [pallet.api :only [lift plan-fn group-spec server-spec]]
   [pallet.crate.automated-admin-user :only [automated-admin-user]]
   [pallet.live-test :only [images test-nodes]]))

(deftest collectd-config-test
  (is (= '[[PIDFile "/pid"]
           [Plugin syslog [[LogLevel info]]]
           [Plugin cpu []]]
         (collectd-config
          [PIDFile "/pid"]
          (Plugin syslog
                  [[LogLevel info]])
          (Plugin cpu [])))))

(deftest add-load-plugin-test
  (is (= '[[LoadPlugin x] [Plugin x []]] (add-load-plugin '[[Plugin x []]]))))

(deftest format-element-test
  (testing "non-blocks"
    (is (= "Collect \"a\" \"b\" \"c\"\n"
           (format-element 0 ['Collect "a" "b" "c"])))
    (is (= "Collect \"a\"\n" (format-element 0 ['Collect "a"])))
    (is (= "    Collect \"a\"\n" (format-element 2 '[Collect "a"])))
    (is (= "Collect \"a\" \"b\"\n" (format-element 0 '[Collect "a" "b"]))))
  (testing "blocks"
    (is (nil? (format-element 0 ['Server []])))
    (is (nil? (format-element 0 ['Server "a" []])))
    (is (= "    <Server>\n      Collect \"a\"\n    </Server>\n"
           (format-element 2 '[Server [[Collect "a"]]])))
    (is (= "<Server \"s\">\n  Collect \"a\" \"b\"\n</Server>\n"
           (format-element 0 '[Server "s" [[Collect "a" "b"]]])))))

(deftest format-config-test
  (is (= "PIDFile \"/pid\"\n"
         (format-config '[[PIDFile "/pid"]])))
  (is (= (str "PIDFile \"/pid\"\n"
              "LoadPlugin syslog\n"
              "LoadPlugin cpu\n"
              "<Plugin \"syslog\">\n"
              "  LogLevel info\n"
              "</Plugin>\n")
         (format-config
          (add-load-plugin
           '[[PIDFile "/pid"]
             [Plugin syslog [[LogLevel info]]]
             (Plugin cpu [])]))))
  (is (= (str
          "PIDFile \"/pid\"\n"
          "LoadPlugin syslog\n"
          "LoadPlugin cpu\n"
          "LoadPlugin memory\n"
          "<Plugin \"syslog\">\n"
          "  LogLevel info\n"
          "</Plugin>\n")
         (format-config
          (add-load-plugin
           (concat
            (collectd-config
             [PIDFile "/pid"]
             (Plugin syslog
                     [[LogLevel info]])
             (Plugin cpu []))
            (collectd-config
             (Plugin memory []))))))))

(deftest plugin-config-test
  (is (= [[:MBean "pfx-gc"
           [:ObjectName "java.lang:type=GarbageCollector,prefix=*"]
           [:InstancePrefix "pfx.gc."]
           [[:Type "gauge"] [:Attribute "CollectionTime"]]]]
         (jmx-mbeans "pfx" [:gc])))
  (is (= [:Plugin :GenericJMX
          [:MBean "pfx-gc"
           [:ObjectName "java.lang:type=GarbageCollector,prefix=*"]
           [:InstancePrefix "pfx.gc."]
           [[:Type "gauge"] [:Attribute "CollectionTime"]]]
          [:Connection
           [[:Host "host"] [:ServiceURL "http://somewhere"]
            [:Collect "pfx-gc"]]]]
         (plugin-config
          :generic-jmx
          {:url "http://somewhere" :host "host"
           :mbeans (jmx-mbeans "pfx" [:gc])}))))

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
         :extends [(collectd settings)]
         :phases {:bootstrap (plan-fn (automated-admin-user)
                                      (package-manager :update))
                  :install (plan-fn (package-manager :update))})}))))
