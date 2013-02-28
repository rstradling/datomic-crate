(ns pallet.crate.datomic
  (:require 
   [pallet.actions :as actions]
   [pallet.api :as api]
   [pallet.crate :as crate]
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

(defn- make-directory
  [dir]
  (println "Let's make a directory = " dir)
  (actions/directory dir :path true :owner "root" :group "root"))

(defn- write-config-file
  [{:keys [user group config-path config] :as settings}]
  (let [
        data-to-write (file-format/name-values config)]
    (actions/remote-file (str config-path "/" config-file-name)
                              :content data-to-write)))

(defn- upstart-file-create
  [{:keys [config-path user config]} root]
  (actions/service-script "datomic"
                          :template datomic-upstart
                          :service-impl :upstart
                          :literal true
                          :values (merge
                                   {:datomic-current-root
                                    (create-current-path root)
                                    :datomic-conf-file (str config-path "/"
                                                            config-file-name)
                                    :datomic-log-file (:log-dir config)
                                    :datomic-user user} {})))

(crate/defplan datomic-settings
  [{:keys [config-file config type version instance-id memory-index-max] :as settings}]
  (let [options (when memory-index-max {:memory-index-max memory-index-max})]
    (crate/assoc-settings :datomic (merge *default-settings* settings options))))

(crate/defplan install-datomic
  "Install datomic"
  [& {:keys [instance-id]}]
  (let [
        settings (crate/get-settings :datomic)
        version (:version settings)
        type (:type settings)
        url (download-url version type)]
    (make-directory "/opt/local")
    (println "Creating the directory /opt/local")
    (actions/user (:user settings) :home datomic-root
                  :shell :false :create-home true :system true)
    (println "after create user")
    (make-datomic-directories settings)
    (println "after making directories")
    (write-config-file settings)
    (println "after config file settings")
    (actions/packages
      :yum ["unzip"]
      :aptitude ["unzip"]
      :pkgin ["unzip"])
      (println "after unzip")
     (actions/remote-directory
       datomic-root
       :url url
       ;:md5 (md5s version)
       :unpack :unzip
       :owner (:user settings)
       :group (:group settings))
     (actions/symbolic-link (str datomic-root "/" (datomic-file-name version type))
                            (create-current-path datomic-root)
                            :action :create
                            :owner (:user settings)
                            :group (:group settings))
    (upstart-file-create settings datomic-root)))

(defn datomic
  [settings]
  (api/server-spec :phases {:settings (api/plan-fn (datomic-settings settings))
                            :configure (api/plan-fn
                                        (install-datomic))}))

