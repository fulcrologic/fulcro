#!/bin/bash

echo "WARNING: Make sure you have no uncommitted changes, and are ok with this script cleaning your repo (removing node,
IDE files, etc." 2>&1
echo "You MUST ALSO be in the top-level directory of your project." 2>&1

ok=boo
while [[ "$ok" != "y" && "$ok" != "n" ]]
do
  echo -n "Continue? [y/n] "
  read ok
done

if [[ "$ok" != "y" ]]; then
  echo "Stopping." 2>&1
  exit 1
fi

git clean -xfd
/usr/bin/perl -pi -e 's/untangled-web/fulcrologic/g' $(git grep -l untangled-web) < /dev/null
git clean -f
/usr/bin/perl -pi -e 's/awkay/fulcrologic/g' $(git grep -l awkay) < /dev/null
git clean -f
/usr/bin/perl -pi -e 's/untangled/fulcro/g' $(git grep -l untangled) < /dev/null
git clean -f
/usr/bin/perl -pi -e 's/Untangled/Fulcro/g' $(git grep -l Untangled) < /dev/null
git clean -f
/usr/bin/perl -pi -e 's/UNTANGLED/FULCRO/g' $(git grep -l UNTANGLED) < /dev/null
git clean -f


