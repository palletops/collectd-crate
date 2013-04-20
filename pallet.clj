;;; Pallet project configuration file

(require
 '[pallet.crate.collectd-test :refer [collectd-test-spec]]
 '[pallet.crates.test-nodes :refer [node-specs]])

(defproject collectd-crate
  :provider node-specs                  ; supported pallet nodes
  :groups [collectd-test-spec])
