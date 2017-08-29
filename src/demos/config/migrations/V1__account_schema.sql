CREATE TABLE settings (
  id                 SERIAL PRIMARY KEY,
  auto_open          BOOLEAN NOT NULL DEFAULT FALSE,
  keyboard_shortcuts BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE account (
  id             SERIAL PRIMARY KEY,
  name           TEXT,
  last_edited_by INTEGER,
  settings_id    INTEGER REFERENCES settings (id),
  created_on     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE member (
  id         SERIAL PRIMARY KEY,
  name       TEXT,
  account_id INTEGER NOT NULL REFERENCES account (id)
);
ALTER TABLE account
  ADD CONSTRAINT account_last_edit_by_fkey FOREIGN KEY (last_edited_by) REFERENCES member (id);

-- Some seed data for the demo
INSERT INTO settings (id, auto_open, keyboard_shortcuts) VALUES (1, true, false);
INSERT INTO settings (id, auto_open, keyboard_shortcuts) VALUES (2, false, false);
INSERT INTO account (id, name, settings_id) values (1, 'Sally', 1);
INSERT INTO account (id, name, settings_id) values (2, 'Bob', 2);
INSERT INTO member (id, name, account_id) values (1, 'Billy', 1);
INSERT INTO member (id, name, account_id) values (2, 'Tom', 1);
INSERT INTO member (id, name, account_id) values (3, 'Tori', 1);
INSERT INTO member (id, name, account_id) values (4, 'Cory', 2);
INSERT INTO member (id, name, account_id) values (5, 'Kady', 2);
