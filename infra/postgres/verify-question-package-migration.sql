-- Returns no rows when every legacy question has at least one item.
SELECT q.id AS package_without_items
FROM questions q
LEFT JOIN question_items qi ON qi.question_id = q.id
GROUP BY q.id
HAVING COUNT(qi.id) = 0;

-- Returns no rows when item ordinals are positive and contiguous.
SELECT question_id
FROM question_items
GROUP BY question_id
HAVING MIN(ordinal) <> 1 OR MAX(ordinal) <> COUNT(*);

-- Returns no rows when normalized cell bindings are unique within a package.
SELECT question_id, normalized_target_cell, COUNT(*)
FROM question_items
WHERE normalized_target_cell IS NOT NULL
GROUP BY question_id, normalized_target_cell
HAVING COUNT(*) > 1;

-- Returns no rows when migrated submissions are item-keyed JSON objects.
SELECT id AS non_object_submission
FROM submissions
WHERE jsonb_typeof(submitted_answer) <> 'object';

-- Inventory only: legacy packages that need a valid binding before sheet sync.
SELECT q.id, q.prompt, q.sheet_config_id, qi.target_cell
FROM questions q
JOIN question_items qi ON qi.question_id = q.id
WHERE q.sheet_config_id IS NULL OR qi.target_cell IS NULL
ORDER BY q.ordinal, qi.ordinal;
