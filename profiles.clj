{:no-checkouts {:checkout-deps-shares ^{:replace true} []},
 :provided {:dependencies [[org.clojure/clojure "1.4.0"]
                           [com.palletops/pallet "0.8.0-RC.11"]]}
 :jclouds {:dependencies [[org.cloudhoist/pallet-jclouds "1.5.2"]
                          [org.jclouds/jclouds-allblobstore "1.5.5"]
                          [org.jclouds/jclouds-allcompute "1.5.5"]
                          [org.jclouds.driver/jclouds-slf4j "1.5.5"
                           :exclusions [org.slf4j/slf4j-api]]
                          [org.jclouds.driver/jclouds-sshj "1.5.5"]]},
 :vmfest {:dependencies [[com.palletops/pallet-vmfest "0.3.0-beta.2"]]},
 :dev {:dependencies [[org.clojure/clojure "1.5.1"]
                      [com.palletops/pallet "0.8.0-RC.11"]
                      [com.palletops/pallet "0.8.0-RC.11" :classifier "tests"]
                      [com.palletops/crates "0.1.1"]
                      [ch.qos.logback/logback-classic "1.0.9"]],
       :plugins [[lein-pallet-release "RELEASE"]
                 [com.palletops/pallet-lein "0.8.0-alpha.1"]
                 [lein-resource "0.3.2"]]}}
