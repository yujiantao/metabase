(ns metabase.models.query-field-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer :all]
   [metabase.query-analysis :as query-analysis]
   [metabase.test :as mt]
   [toucan2.core :as t2]
   [toucan2.tools.with-temp :as t2.with-temp]))

(def ^:private query-field-keys [:card_id :field_id :explicit_reference])

(defn- qf->map [query-field]
  (select-keys query-field query-field-keys))

(defn- query-fields-for-card
  [card-id]
  (t2/select-fn-set qf->map :model/QueryField
                    :card_id card-id))

(defn- do-with-test-setup [f]
  (binding [query-analysis/*parse-queries-in-test?* true]
    (let [table-id (mt/id :orders)
          tax-id   (mt/id :orders :tax)
          total-id (mt/id :orders :total)]
      (t2.with-temp/with-temp [:model/Card {card-id :id}
                               {:dataset_query (mt/native-query {:query "SELECT NOT_TAX, TOTAL FROM orders"})}]
        (try
          (f {:card-id  card-id
              :tax-id   tax-id
              :total-id total-id
              :table-id table-id})
          (finally
            (t2/delete! :model/QueryField :card_id card-id)))))))

(defmacro ^:private with-test-setup
  "Creates a new card that queries one column that exists (TOTAL) and one that does not (NOT_TAX). Anaphorically
  provides `card-id`, `table-id`, `tax-id`, and `total-id`."
  [& body]
  `(do-with-test-setup (fn [{:keys [~'table-id ~'tax-id ~'total-id ~'card-id]}]
                         ~@body)))

(defn- trigger-parse!
  "Update the card to an arbitrary query; defaults to querying the two columns that do exist: TAX and TOTAL"
  ([card-id]
   (trigger-parse! card-id "SELECT TAX, TOTAL FROM orders"))
  ([card-id query]
   (if (string? query)
     (t2/update! :model/Card card-id {:dataset_query (mt/native-query {:query query})})
     (t2/update! :model/Card card-id {:dataset_query query}))))

;;;;
;;;; Actual tests
;;;;

(deftest query-fields-created-by-queries-test
  (with-test-setup
    (let [total-qf {:card_id          card-id
                    :field_id         total-id
                    :explicit_reference true}
          tax-qf   {:card_id          card-id
                    :field_id         tax-id
                    :explicit_reference true}]

      (testing "A freshly created card has relevant corresponding QueryFields"
        (is (= #{total-qf}
               (query-fields-for-card card-id))))

      (testing "Adding new columns to the query also adds the QueryFields"
        (trigger-parse! card-id)
        (is (= #{tax-qf total-qf}
               (query-fields-for-card card-id))))

      (testing "Removing columns from the query removes the QueryFields"
        (trigger-parse! card-id "SELECT tax, not_total FROM orders")
        (is (= #{tax-qf}
               (query-fields-for-card card-id))))

      (testing "Columns referenced via field filters are still found"
        (trigger-parse! card-id
                        (mt/native-query {:query "SELECT tax FROM orders WHERE {{adequate_total}}"
                                          :template-tags {"adequate_total"
                                                          {:type         :dimension
                                                           :name         "adequate_total"
                                                           :display-name "Total is big enough"
                                                           :dimension    [:field (mt/id :orders :total)
                                                                          {:base-type :type/Number}]
                                                           :widget-type  :number/>=}}}))
        (is (= #{tax-qf total-qf}
               (query-fields-for-card card-id)))))))

(deftest bogus-queries-test
  (with-test-setup
    (testing "Updating a query with bogus columns does not create QueryFields"
      (trigger-parse! card-id "SELECT DOES, NOT_EXIST FROM orders")
      (is (empty? (t2/select :model/QueryField :card_id card-id))))))

(deftest wildcard-test
  (with-test-setup
    (let [total-qf {:card_id          card-id
                    :field_id         total-id
                    :explicit_reference false}
          tax-qf   {:card_id          card-id
                    :field_id         tax-id
                    :explicit_reference false}]
      (testing "simple select *"
        (trigger-parse! card-id "select * from orders")
        (let [qfs (query-fields-for-card card-id)]
          (is (= 9 (count qfs)))
          (is (not-every? :explicit_reference qfs))
          (is (set/subset? #{total-qf tax-qf} qfs)))))))

(deftest table-wildcard-test
  (with-test-setup
    (let [total-qf {:card_id          card-id
                    :field_id         total-id
                    :explicit_reference true}
          tax-qf   {:card_id          card-id
                    :field_id         tax-id
                    :explicit_reference true}]
      (testing "mix of select table.* and named columns"
        (trigger-parse! card-id "select p.*, o.tax, o.total from orders o join people p on p.id = o.user_id")
        (let [qfs (query-fields-for-card card-id)]
          (is (= (+ 13 #_people 2 #_tax-and-total 1 #_o.user_id)
                 (count qfs)))
          ;; 13 total, but id is referenced directly
          (is (= 12 (t2/count :model/QueryField :card_id card-id :explicit_reference false)))
          ;; subset since it also includes the PKs/FKs
          (is (set/subset? #{total-qf tax-qf}
                           (t2/select-fn-set qf->map :model/QueryField :card_id card-id :explicit_reference true))))))))
