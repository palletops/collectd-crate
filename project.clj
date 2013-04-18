(defproject com.palletops/collectd-crate "0.8.0-SNAPSHOT"
  :description "Crate for collectd installation"
  :url "http://github.com/palletops/collectd-crate"
  :license {:name "All rights reserved"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.palletops/pallet "0.8.0-beta.7"]]
  :resource {:resource-paths ["doc-src"]
             :target-path "target/classes/pallet_crate/collectd_crate/"
             :includes [#"doc-src/USAGE.*"]}
  :prep-tasks ["resource" "crate-doc"])
