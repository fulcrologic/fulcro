-- :name all-people :? :*
SELECT id, name, age FROM person;

-- :name next-person-id :? :1
SELECT nextval('person_id_seq') AS id;

-- :name insert-person :! :1
INSERT INTO person (id, name, age) VALUES (:id, :name, :age);