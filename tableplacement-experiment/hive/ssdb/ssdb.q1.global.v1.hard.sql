SELECT SUM(v1),COUNT(*) FROM cycle
WHERE x BETWEEN 0 and 15000
AND   y BETWEEN 0 and 15000;
