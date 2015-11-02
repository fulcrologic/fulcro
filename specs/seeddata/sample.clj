;; Sample file for generating seed data. You can use full-blown clojure in here.
;; In general, test will depend on this data, so once a seed file is committed, it generally should
;; not be modified; however, you can base a new seed file on an existing one simply by pulling it
;; into your new one.

;; Set your namespace to match your file.
(ns seeddata.sample
  ;; optionally bring in other things, including other seed data:
  ;;    (:require seeddata.base-user)
  )

;; The seed-data must be a single transaction
;; (which is a list of maps or a list of lists, as described in the
;; datomic docs). This makes it easier to compose seed data and refer to prior 
;; temporary IDs.
;;
;; Desired API:
;;
;;     (gen-user :id/USER1) 
;;
;;     (defn auth-role [idkeyword entitlementIDs]
;;      (entity/gen {
;;         :db/id idkeyword
;;         :authorization-role/name "NWMgr"
;;         :authorization-role/entitlement (vec entitlementIDs)
;;         }))
;;
;;     (defn gen-user [idkeyword roleIDs]
;;      (assert (vec? roleIDs))
;;      (entity/gen {
;;         :db/id idkeyword
;;         :user/name "Sam"
;;         :user/authorization-role roleIDs
;;         :user/email "sam@example.net" }))
;;
;;     (defn gen-account [idkeyword userIDs]
;;      (assert (vec? userIDs))
;;      (entity/gen {
;;         :db/id idkeyword
;;         :account/account-id "Account 1"
;;         :account/account-name "Account 1" 
;;         :account/user (vec userIDs)
;;         }))
;;
;; TODO: Continue defining API here...
;;
;;
;;
;;
;;
;;
;;
;;...........
;;     (ns seeddata.base-user)
;;     (def seed-data
;;            [{:db/id #db/id[:db.part/user -1]
;;              :person/name "Bob"}]
;;     )
;;         
;;
;;     (def seed-data 
;;          (concat
;;            seeddata.base-user/seed-data
;;            [{:db/id #db/id[:db.part/user -1] ;; add a spouse attr to Bob from base users
;;              :person/spouse #db/id[:db.part/user -2]}
;;             {:db/id #db/id[:db.part/user -2] ;; Add Alice with cross-ref to Bob
;;              :person/name "Alice"
;;              :person/spouse #db/id[:db.part/user -1]}]
;;          )
;;     )
;;
;; Now seeddata.sample/seed-data includes the base user data as well as some additional bits.
