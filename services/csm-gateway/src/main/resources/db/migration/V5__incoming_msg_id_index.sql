-- flyway:executeInTransaction=false
-- specs/15-csm-gateway.md §6.4: a pacs.002 without OrgnlTxRef/DbtrAgt names an incoming transfer by its pacs.008's
-- MsgId; created CONCURRENTLY, in its own migration (00 §9)
CREATE INDEX CONCURRENTLY IF NOT EXISTS incoming_msg_id ON incoming_transfers (msg_id);
