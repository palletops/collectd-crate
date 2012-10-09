(ns pallet.crate.collectd-test
  (:use
   clojure.test
   pallet.crate.collectd
   [pallet.actions :only [package-manager]]
   [pallet.algo.fsmop :only [complete?]]
   [pallet.api :only [lift plan-fn group-spec server-spec]]
   [pallet.crate.automated-admin-user :only [automated-admin-user]]
   [pallet.live-test :only [images test-nodes]]))

(deftest config-block-test
  (is (= '[:pallet.crate.collectd/config-block Plugin powerdns
           [:pallet.crate.collectd/config-block Server "s"
            [:pallet.crate.collectd/config Collect "l"]]
           [:pallet.crate.collectd/config-block Recursor "r_n"
            [:pallet.crate.collectd/config Collect "qs"]
            [:pallet.crate.collectd/config Collect ["ch" "cm"]]]
           [:pallet.crate.collectd/config LocalSocket "/ls"]]
         (eval (config-block
                '(Plugin powerdns
                         (Server "s"
                                 Collect "l")
                         (Recursor "r_n"
                                   Collect "qs"
                                   Collect ["ch" "cm"])
                         LocalSocket "/ls"))))))

(deftest collectd-plugin-config-test
  (is (= '[:pallet.crate.collectd/config-block
           Plugin s [:pallet.crate.collectd/config-block Collect "l"]]
         (collectd-plugin-config s (Collect "l")))))

(deftest collectd-config-test
  (is (= '[:pallet.crate.collectd/config-block
           pallet.crate.collectd/implicit nil
           [:pallet.crate.collectd/config PIDFile "/pid"]]
         (collectd-config PIDFile "/pid")))
  (is (= '[:pallet.crate.collectd/config-block
           pallet.crate.collectd/implicit nil
           [:pallet.crate.collectd/config PIDFile (script "/pid")]]
         (collectd-config PIDFile '(script "/pid"))))
  (is (= '[:pallet.crate.collectd/config-block
           pallet.crate.collectd/implicit nil
           [:pallet.crate.collectd/config-block Plugin powerdns
            [:pallet.crate.collectd/config-block Server "s"
             [:pallet.crate.collectd/config Collect "l"]]
            [:pallet.crate.collectd/config-block Recursor "r_n"
             [:pallet.crate.collectd/config Collect "qs"]
             [:pallet.crate.collectd/config Collect ["ch" "cm"]]]
            [:pallet.crate.collectd/config LocalSocket "/ls"]]]
         (collectd-config
          (Plugin powerdns
                  (Server "s"
                          Collect "l")
                  (Recursor "r_n"
                            Collect "qs"
                            Collect ["ch" "cm"])
                  LocalSocket "/ls")))))

(deftest format-scoped-blocks-test
  (is (= "\"a\"" (format-scoped-blocks "a")))
  (is (= "\"a\" \"b\" \"c\"" (format-scoped-blocks ["a" "b" "c"])))
  (is (= "Collect \"a\""
         (format-scoped-blocks
          [:pallet.crate.collectd/config 'Collect "a"])))
  (is (= "Collect \"a\" \"b\""
         (format-scoped-blocks
          [:pallet.crate.collectd/config 'Collect ["a" "b"]]))))

(deftest format-config-test
  (is (= "PIDFile \"/pid\"\n\n"
         (format-config (collectd-config PIDFile "/pid"))))
  (is (= "PIDFile \"/pid\"\nLoadPlugin syslog\nLoadPlugin cpu\n<Plugin syslog>\nLogLevel info\n</Plugin>\n\n"
         (format-config
          (collectd-config
           PIDFile "/pid"
           (Plugin syslog
                   LogLevel 'info)
           (Plugin cpu)))))
  (is (= "PIDFile \"/pid\"\nLoadPlugin syslog\nLoadPlugin cpu\nLoadPlugin memory\n<Plugin syslog>\nLogLevel info\n</Plugin>\n\n\n"
         (format-config
          (conj
           (collectd-config
            PIDFile "/pid"
            (Plugin syslog
                    LogLevel 'info)
            (Plugin cpu))
           (collectd-plugin-config
            memory))))))

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
