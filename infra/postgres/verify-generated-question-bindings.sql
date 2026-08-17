-- All rows returned by these queries require investigation.
SELECT b.*
FROM question_item_sheet_bindings b
LEFT JOIN question_items qi ON qi.id = b.item_id
WHERE qi.id IS NULL;

SELECT classroom_id, source_sheet_config_id, UPPER(BTRIM(source_worksheet)), UPPER(BTRIM(source_cell)), COUNT(*)
FROM question_item_sheet_bindings
GROUP BY classroom_id, source_sheet_config_id, UPPER(BTRIM(source_worksheet)), UPPER(BTRIM(source_cell))
HAVING COUNT(*) > 1;

SELECT classroom_id, answer_sheet_config_id, UPPER(BTRIM(answer_worksheet)), UPPER(BTRIM(target_cell)), COUNT(*)
FROM question_item_sheet_bindings
GROUP BY classroom_id, answer_sheet_config_id, UPPER(BTRIM(answer_worksheet)), UPPER(BTRIM(target_cell)
)
HAVING COUNT(*) > 1;

