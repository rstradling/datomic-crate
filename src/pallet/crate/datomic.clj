;; A Clojure library designed to work with pallet 0.8.0 and create a
;; datomic instance.  This instance has been tested on Ubuntu and RHEL with the
;; free version of datomic.  I hope that this works with non-free
;; versions as well but I don't know that process.  Pull requests are
;; welcome. It is expected that you have installed Java in order for
;; the service to start correctly.   This crate will
;; * Download datomic based upon the type and version
;; * Unzip datomic into /opt/local/datomic/<version>
;; * Update a soft lknk /opt/local/datomic/current ->
;;                                  /opt/local/datomic/version
;; * Install the upstart service (IT DOES NOT RUN THE SERVICE)
(ns pallet.crate.datomic
  (:require 
   pallet.node
   [pallet.actions :as actions]
   [pallet.action :as action]
   [pallet.api :as api]
   [pallet.crate :as crate]
   [pallet.template :as templ]
   [pallet.config-file.format :as file-format]))

(def datomic-upstart "crate/datomic/datomic.conf")
(def datomic-root "/opt/local/datomic")

(def config-file-name "transactor.properties")

(def ^{:dynamic true} *default-settings*
  {
   :version "0.8.3789"
   :type "free"
   :user "datomic"
   :group "datomic"
   :config-path "/etc/datomic"
   :config {:protocol "free", :host "localhost" :port "4334"
            :data-dir "/var/lib/datomic/data"
            :log-dir "/var/log/datomic"}})

(def md5s {})

(defn- datomic-file-name
  "Returns the name of the datomic to download (minus the file extension)
   The version specifies which version and the type specifies whether it is free or not"
  [version type]
  (format "datomic-%s-%s" type version))

(defn create-current-path
  [src-path]
  (str src-path "/current"))

(defn- download-url
  "The url for downloading datomic"
  [version type]
  (format "http://downloads.datomic.com/%s/%s.zip" version  (datomic-file-name version type)))

(defn- make-datomic-directories
  "Make the datomic directories"
  [{:keys [config-path user group] :as settings}]
  (let [config (:config settings)]
    (actions/directory config-path :path true
                       :owner user :group group)
    (actions/directory (:data-dir config) :path true
                       :owner user :group group)
    (actions/directory (:log-dir config) :path true
                       :owner user :group group)))

(defn- write-config-file
  "Writes out the config file with user and group permissions
   to config-path the config data."
  [{:keys [user group config-path config] :as settings}]
  (let [
        data-to-write (file-format/name-values config)]
    (actions/remote-file (str config-path "/" config-file-name)
                              :content data-to-write)))

(defn- upstart-file-create
  "Upload the datomic upstart script from the resources directory and
   replace out template values"
  [{:keys [config-path user config] :as settings} root]
  (actions/service-script "datomic"
                          :template datomic-upstart
                          :service-impl :upstart
                          :literal true
                          :values {:datomic-current-root
                                   (create-current-path root)
                                   :datomic-conf-file (str config-path "/"
                                                           config-file-name)
                                   :datomic-log-file (:log-dir config)
                                   :datomic-user user}))

(crate/defplan datomic-settings
  "Captures settings for datomic. Please see *default-settings* for more information about what
   the defaults are.
-  :type Type of the transactor.  This is used to concatenate to figure out what file should be downloaded.
-  :version Version to download
-  :config
   -  :protocol Protocol for datomic to use
   -  :host The host for datomic to use
   -  :port The port to start datomic on (remember it will use 3 consecutive ports starting at port)
   -  :log-dir The log directory for datomic
   -  :data-dir The data directory for datomic
   -  :memory-index-max Optional"
  [{:keys [config-file config type version instance-id] :as settings}]
  (let [options (when (:memory-index-max config) {:memory-index-max (:memory-index-max config)})
        node (crate/target-node)
        private_ip (pallet.node/private-ip node)
        public_ip (pallet.node/primary-ip node)]
    (crate/assoc-settings :datomic (merge *default-settings* (assoc settings :config (assoc config :host private_ip :alt-host public_ip)) options) {:instance-id instance-id})))

(crate/defplan install-datomic
  "Install datomic"
  [& {:keys [instance-id]}]
  (let [
        settings (crate/get-settings :datomic {:instance-id instance-id :default ::no-settings})
        {:keys [version type user group ]} settings
        version (:version settings)
        type (:type settings)
        url (download-url version type)]
    ;; Ensures that this directory call is before the actions/user
    ;; call otherwise the actions/user call would fail because
    ;; /opt/local directory is not created yet
    (action/with-action-options {:always-before #{actions/user}}
     (actions/directory "/opt/local" :path true :owner "root" :group "root"))
    (actions/user user :home datomic-root
                   :shell :false :create-home true :system true)
    (make-datomic-directories settings)
    (write-config-file settings)
    (actions/packages
     :yum ["unzip"]
     :aptitude ["unzip"]
     :pkgin ["unzip"])

    (actions/remote-directory
     datomic-root
     :url url
                                        ;:md5 (md5s version)
     :unpack :unzip
     :owner user
     :group group)
    (actions/symbolic-link (str datomic-root "/" (datomic-file-name version type))
                           (create-current-path datomic-root)
                           :action :create
                           :owner user
                           :group group)
    (upstart-file-create settings datomic-root)))

(defn datomic
  "Returns a service-spec for installing java"
  [settings]
  (api/server-spec :phases {:settings (api/plan-fn (datomic-settings settings))
                            :configure (api/plan-fn
                                        (install-datomic))}))

