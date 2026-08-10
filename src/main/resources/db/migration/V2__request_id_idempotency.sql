ALTER TABLE diet_request_trace
    ADD COLUMN request_id varchar(128) NULL AFTER trace_id,
    ADD COLUMN response_json json NULL AFTER trace_json;

UPDATE diet_request_trace
SET request_id = trace_id
WHERE request_id IS NULL;

ALTER TABLE diet_request_trace
    MODIFY COLUMN request_id varchar(128) NOT NULL,
    ADD UNIQUE INDEX uk_request_trace_request (user_id, session_id, request_id);
