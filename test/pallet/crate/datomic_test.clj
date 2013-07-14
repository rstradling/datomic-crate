(ns pallet.crate.datomic-test
  (:require [clojure.test :refer [deftest is testing]]
            [pallet.crate.datomic :as datomic]
            [pallet.action :refer [with-action-options]]
            [pallet.actions :refer [directory user]]
            [clojure.pprint :refer [pprint]]) 
  (:use pallet.test-utils))

(deftest create-current-path-test 
  (testing "Current path works"
    (is (= "free/current" (datomic/create-current-path "free")))))

(def settings
  {:version "0.8.3889"
   :type "free"
   :user "datomic"
   :group "datomic"
   :config-file "/etc/datomic"
   :config {:protocol "free" :host "localhost" :port "4334"
            :data-dir "/var/lib/datomic/data"
            :log-dir "/var/log/datomic"}})

