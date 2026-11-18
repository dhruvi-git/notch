"""Runs every SQL statement in PortfolioRepository and RecruitRepository, verbatim, against PostgreSQL.

Checks that the queries parse and behave (upserts, IN lists, joins, cascades, ownership) and that
the migrations' constraints reject bad data. Everything runs in one transaction that is rolled back,
so it leaves no data behind. Run against a database the API has migrated (start the API once):

    pip install psycopg2-binary
    python api/scripts/check_repository_sql.py          # NOTCH_DB_DSN overrides the connection
"""
import os, re, sys, uuid, datetime as dt
import psycopg2, psycopg2.extras
psycopg2.extras.register_uuid()

def extract(path):
    src = open(path).read()
    out = {}
    for m in re.finditer(r'public [^\n=;]*?\b(\w+)\(([^)]*)\)\s*\{', src):
        name, start = m.group(1), m.end()
        depth, i = 1, start
        while depth:
            if src[i] == '{': depth += 1
            elif src[i] == '}': depth -= 1
            i += 1
        body = src[start:i]
        sqls = re.findall(r'jdbc\.sql\(\s*(?:"""(.*?)"""|"((?:[^"\\]|\\.)*)")', body, re.S)
        out.setdefault(name, []).extend([(a or b) for a, b in sqls])
    return out

def to_pg(sql):
    sql = re.sub(r'IN \(:(\w+)\)', r'IN %(\1)s', sql)
    return re.sub(r'(?<![:\w]):(\w+)', r'%(\1)s', sql)

base = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'java', 'dev', 'notch', 'api') + os.sep
P = extract(base + os.path.join('portfolio', 'PortfolioRepository.java'))
R = extract(base + os.path.join('recruit', 'RecruitRepository.java'))
con = psycopg2.connect(os.environ.get('NOTCH_DB_DSN', 'host=localhost port=5433 dbname=notch user=notch password=notch'))
cur = con.cursor()
ok = fail = 0

def run(repo, method, i=0, expect_error=None, **params):
    global ok, fail
    sql = to_pg(repo[method][i])
    for k, v in list(params.items()):
        if isinstance(v, list): params[k] = tuple(v)
    cur.execute('SAVEPOINT q')
    try:
        cur.execute(sql, params)
        rows = cur.fetchall() if cur.description else cur.rowcount
        cur.execute('RELEASE SAVEPOINT q')
        if expect_error:
            fail += 1; print(f'FAIL {method}[{i}]: expected {expect_error}, got success'); return None
        ok += 1
        return rows
    except psycopg2.Error as e:
        cur.execute('ROLLBACK TO SAVEPOINT q')
        if expect_error and expect_error in type(e).__name__ + str(e):
            ok += 1; return 'error'
        fail += 1; print(f'FAIL {method}[{i}]: {type(e).__name__}: {str(e).strip()}\n  SQL: {sql.strip()[:300]}'); return None

def check(cond, label):
    global ok, fail
    if cond: ok += 1
    else: fail += 1; print('FAIL check:', label)

now = dt.datetime.now(dt.timezone.utc)
x = uuid.uuid4().hex[:6]   # suffix: handles stay unique in a database that already has data
A, B, C = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
for u, h in ((A, 'asha'), (B, 'ben'), (C, 'chen')):
    cur.execute('INSERT INTO users (id, handle) VALUES (%s, %s)', (u, h))

# catalogue
check(len(run(P, 'skills')) >= 90, 'skills seeded')
check(len(run(P, 'aliases')) >= 90, 'aliases seeded')

# profiles
run(P, 'upsertProfile', u=A, h='asha-r-' + x, headline='Data analyst', domain=None, pub=True)
run(P, 'upsertProfile', u=A, h='asha-r-' + x, headline='Data analyst, Pune', domain='Data', pub=True)   # update path
run(P, 'upsertProfile', u=B, h='ben-k-' + x, headline=None, domain=None, pub=True)
run(P, 'upsertProfile', u=C, h='chen-' + x, headline=None, domain=None, pub=False)
run(P, 'upsertProfile', expect_error='UniqueViolation', u=C, h='asha-r-' + x, headline=None, domain=None, pub=False)
run(P, 'upsertProfile', expect_error='CheckViolation', u=C, h='Bad Handle', headline=None, domain=None, pub=False)
check(run(P, 'profile', u=A)[0][2] == 'Data analyst, Pune', 'profile updated')
check(len(run(P, 'publicProfile', h='asha-r-' + x)) == 1, 'public profile found')
check(len(run(P, 'publicProfile', h='chen-' + x)) == 0, 'private profile hidden')
check({'asha-r-' + x, 'ben-k-' + x} <= {r[1] for r in run(P, 'publicProfiles')} and 'chen-' + x not in {r[1] for r in run(P, 'publicProfiles')}, 'public profiles listed')

# evidence
repoA, projA, chatA, repoB = (uuid.uuid4() for _ in range(4))
ev = dict(description=None, fork=False, dup=None, depth=0.0, root=None, tree=None, last=now)
run(P, 'insertEvidence', id=repoA, u=A, kind='GITHUB_REPO', title='sales-dashboard', url='https://github.com/asha/sales-dashboard',
    commits=146, weeks=9, **{**ev, 'root': 'root-a-' + x, 'tree': 'tree-a-' + x})
run(P, 'insertEvidence', id=projA, u=A, kind='PROJECT', title='Fest analytics', url=None, commits=0, weeks=0, **ev)
run(P, 'insertEvidence', id=chatA, u=A, kind='CHAT_EXPORT', title='Discussed SQL in 14 ChatGPT conversations', url=None,
    commits=0, weeks=0, **{**ev, 'depth': 0.72})
run(P, 'insertEvidence', expect_error='CheckViolation', id=uuid.uuid4(), u=A, kind='TWEET', title='x', url=None, commits=0, weeks=0, **ev)
run(P, 'insertEvidence', expect_error='CheckViolation', id=uuid.uuid4(), u=A, kind='PROJECT', title='x', url=None, commits=-1, weeks=0, **ev)
run(P, 'linkSkills', e=repoA, s=17, a=True); run(P, 'linkSkills', e=repoA, s=1, a=True)
run(P, 'linkSkills', e=repoA, s=17, a=True)  # ON CONFLICT DO NOTHING
run(P, 'linkSkills', e=projA, s=23, a=True); run(P, 'linkSkills', e=chatA, s=25, a=False)
check(run(P, 'setLinkApproved', a=True, e=chatA, u=B, s=25) == 0, 'cannot approve another user\'s link')
check(run(P, 'setLinkApproved', a=True, e=chatA, u=A, s=25) == 1, 'approve own link')

links = run(P, 'evidenceFor', 0, users=[A, B])
check(len(links) == 4, f'links for A and B: {len(links)}')
rows = run(P, 'evidenceFor', 1, users=[A, B])
check(len(rows) == 3, 'evidence rows')
check(run(P, 'githubEvidence', u=A) == [(repoA, 'https://github.com/asha/sales-dashboard')], 'github evidence by url')
check(run(P, 'updateRepoEvidence', title='sales-dashboard', description='d', commits=150, weeks=10, dup=None, root='root-a-' + x,
          tree='tree-a2', last=now, id=repoA, u=A) == 1, 'update repo evidence')
check(run(P, 'updateRepoEvidence', title='x', description=None, commits=1, weeks=1, dup=None, root=None, tree=None, last=now,
          id=repoA, u=B) == 0, 'cannot update another user\'s evidence')
run(P, 'replaceLinks', 0, e=repoA); run(P, 'linkSkills', e=repoA, s=20, a=True)

# fingerprints and duplicates
run(P, 'insertEvidence', id=repoB, u=B, kind='GITHUB_REPO', title='copy', url='https://github.com/ben/copy',
    commits=10, weeks=2, **{**ev, 'dup': repoA, 'root': 'root-a-' + x, 'tree': 'tree-b-' + x})
fps = {r[0] for r in run(P, 'allFingerprints')}
check({'root-a-' + x, 'tree-a2', 'tree-b-' + x} <= fps, 'fingerprints listed')
owners = run(P, 'fingerprintOwners', sha='root-a-' + x)
check([o[0] for o in owners] == [repoA], 'owners exclude confirmed duplicates')
check(run(P, 'fingerprintOwners', sha='nope') == [], 'unknown fingerprint')

# claimed and hidden skills
run(P, 'setUserSkill', u=A, s=2, c=True, h=False); run(P, 'setUserSkill', u=A, s=2, c=True, h=True)  # upsert
check(run(P, 'userSkillsFor', users=[A, B]) == [(A, 2, True, True)], 'user skills upsert')

# deletes
check(run(P, 'deleteChatEvidence', u=A) == 1, 'delete chat evidence')
check(run(P, 'deleteEvidence', 1, u=B, ids=[projA]) == 0, 'cannot delete another user\'s evidence')
check(run(P, 'deleteEvidence', 0, e=projA, u=A) == 1, 'delete one evidence item')

# recruiting
drive, other = uuid.uuid4(), uuid.uuid4()
run(R, 'insertDrive', id=drive, r=C, t='Analytics interns', d='Must have SQL.')
check(len(run(R, 'drive', id=drive, r=C)) == 1, 'own drive')
check(run(R, 'drive', id=drive, r=A) == [], 'other recruiter cannot see drive')
check(len(run(R, 'drives', r=C)) == 1, 'list drives')
role1, role2 = uuid.uuid4(), uuid.uuid4()
def replace_roles(roles):
    run(R, 'replaceRoles', 0, d=drive); run(R, 'replaceRoles', 1, d=drive)
    for pos, (rid, name, seats, thr, reqs) in enumerate(roles):
        run(R, 'replaceRoles', 2, id=rid, d=drive, n=name, s=seats, t=thr, p=pos)
        for sk, w, m, l in reqs:
            run(R, 'replaceRoles', 3, r=rid, s=sk, w=w, m=m, l=l)
replace_roles([(role1, 'Data analyst', 3, 60.0, [(17, 3.0, True, 'VERIFIED'), (1, 3.0, True, 'VERIFIED'), (23, 2.0, False, 'SHOWN')]),
               (role2, 'Backend', 2, 50.0, [(2, 3.0, True, 'VERIFIED')])])
reqs = run(R, 'roles', 0, d=drive); roles = run(R, 'roles', 1, d=drive)
check(len(reqs) == 4 and [r[1] for r in roles] == ['Data analyst', 'Backend'], 'roles and requirements in order')
run(R, 'replaceRoles', 2, expect_error='CheckViolation', id=uuid.uuid4(), d=drive, n='Bad', s=-1, t=50.0, p=9)
run(R, 'replaceRoles', 2, expect_error='CheckViolation', id=uuid.uuid4(), d=drive, n='Bad', s=1, t=101.0, p=9)
run(R, 'replaceRoles', 3, expect_error='CheckViolation', r=role1, s=25, w=0.0, m=False, l='SHOWN')
run(R, 'replaceRoles', 3, expect_error='CheckViolation', r=role1, s=25, w=1.0, m=False, l='EXPERT')
runId = uuid.uuid4()
run(R, 'insertRun', 0, id=runId, d=drive, seats=5, greedy=4, paths=1, ms=0.8, at=now)
run(R, 'insertRun', 1, run=runId, role=role1, u=A, s=76.0); run(R, 'insertRun', 1, run=runId, role=role2, u=B, s=71.0)
run(R, 'insertRun', 1, expect_error='UniqueViolation', run=runId, role=role2, u=A, s=50.0)   # one seat per candidate
check(run(R, 'latestRun', d=drive)[0][0] == runId, 'latest run')
check([p[2] for p in run(R, 'placements', run=runId)] == ['asha-r-' + x, 'ben-k-' + x], 'placements with handles, best first')
replace_roles([(uuid.uuid4(), 'Data analyst', 1, 50.0, [(17, 1.0, True, 'SHOWN')])])
check(run(R, 'latestRun', d=drive) == [], 'saving roles clears old runs')
check(run(R, 'deleteDrive', id=drive, r=A) == 0 and run(R, 'deleteDrive', id=drive, r=C) == 1, 'delete own drive only')

con.rollback()   # leave nothing behind
print(f'{ok} passed, {fail} failed')
sys.exit(1 if fail else 0)
