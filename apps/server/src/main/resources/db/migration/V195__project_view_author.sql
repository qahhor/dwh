-- ADR-0013, 2.5; ADR-0026: the analytics dashboard counts only the projects the viewer may see, and the project
-- predicate reads the project's author, so the published view carries it.
create or replace view ms_task_pub_projects as
select id, name, description, archived_at is not null as archived, created_by
from ms_task_projects;
