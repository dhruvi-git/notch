"""Runs every SQL statement in PortfolioRepository, RecruitRepository and OwnershipRepository, verbatim, against PostgreSQL.

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
    # inline shared column lists: "SELECT " + COLUMNS + """ ... becomes one text block
    for name, body in re.findall(r'static final String (\w+) = """(.*?)""";', src, re.S):
        src = re.sub(r'"((?:[^"\\]|\\.)*)" \+ ' + name + r' \+ """', lambda m: '"""' + m.group(1) + body + '\n', src)
        src = re.sub(r'"((?:[^"\\]|\\.)*)" \+ ' + name + r' \+ "((?:[^"\\]|\\.)*)"', lambda m: '"""' + m.group(1) + body + m.group(2) + '"""', src)
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
O = extract(base + os.path.join('ownership', 'OwnershipRepository.java'))
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
rows = run(P, 'evidenceFor', 2, users=[A, B])
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

# ownership checks
chk, chk2 = uuid.uuid4(), uuid.uuid4()
check(len(O['create']) == 2 and 'c.plan_covered FROM ownership_checks' in ' '.join(to_pg(O['check'][0]).split()), 'ownership SQL extracted with shared columns')
run(O, 'create', 0, id=chk, u=A, e=repoA, deadline=now + dt.timedelta(minutes=12), by='templates', elements=9, covered=8)
q0, q1 = uuid.uuid4(), uuid.uuid4()
for qid, pos in ((q0, 0), (q1, 1)):
    run(O, 'create', 1, id=qid, c=chk, pos=pos, path='churn.py', start=10, end=24, lang='python', code='def f():\n  pass',
        concept='Joins' if pos else None, prompt='Walk me through f.', rubric='join|merge\nkey|on', ids='f\ndf', covers='SQL\nchurn.py')
run(O, 'create', 1, expect_error='UniqueViolation', id=uuid.uuid4(), c=chk, pos=0, path='x', start=1, end=2, lang='python',
    code='x', concept=None, prompt='p', rubric='a|b', ids='', covers='')
run(O, 'create', 0, expect_error='CheckViolation', id=uuid.uuid4(), u=A, e=repoA, deadline=now, by='guesswork', elements=1, covered=1)
check(len(run(O, 'check', id=chk, u=A)) == 1, 'own check')
check(run(O, 'check', id=chk, u=B) == [], 'other user cannot read the check')
check(run(O, 'openCheck', u=A, e=repoA)[0][0] == chk, 'open check found')
check(run(O, 'lastFinished', u=A, e=repoA) == [(None,)], 'nothing finished yet')
check(run(O, 'publicCheck', id=chk) == [], 'open checks are not public')
check(run(O, 'saveAnswer', a='It merges on customer_id.', ms=42000, p=0, f=1, c=chk, pos=0) == 1, 'answer saved')
check(run(O, 'saveAnswer', a='x', ms=1, p=0, f=0, c=chk, pos=7) == 0, 'no such question')
run(O, 'saveAnswer', expect_error='CheckViolation', a='x', ms=-5, p=0, f=0, c=chk, pos=1)
qs = run(O, 'questions', c=chk)
check([q[1] for q in qs] == [0, 1] and qs[0][12] == 'It merges on customer_id.' and qs[0][16] == 1, 'questions in order with answers')
run(O, 'finish', 0, s=72, f='Named the join key.', flags='Left the page 2 times', id=q0)
run(O, 'finish', 0, s=0, f='Not answered.', flags='', id=q1)
check(run(O, 'finish', 1, s=72, d=True, summary='Defended', by='rubric', id=chk) == 1, 'check finished')
check(run(O, 'finish', 1, s=10, d=False, summary='x', by='rubric', id=chk) == 0, 'a finished check cannot be finished again')
check(run(O, 'openCheck', u=A, e=repoA) == [], 'no open check after finishing')
check(run(O, 'lastFinished', u=A, e=repoA)[0][0] is not None, 'finish time recorded')
check(len(run(O, 'publicCheck', id=chk)) == 1, 'finished check on a public profile is public')
check(run(O, 'header', id=chk)[0][0] == 'asha-r-' + x, 'public header has the handle')
# a second, failed attempt: the profile still shows the best (defended) one
run(O, 'create', 0, id=chk2, u=A, e=repoA, deadline=now, by='model', elements=5, covered=5)
run(O, 'finish', 1, s=40, d=False, summary='Not yet', by='model', id=chk2)
own = [r for r in run(P, 'evidenceFor', 1, users=[A, B]) if r[0] == repoA]
check(len(own) == 1 and own[0][1] == chk and own[0][2] == 72 and own[0][3] is True and own[0][5] == 2, f'evidence shows best check of 2: {own}')
cur.execute("UPDATE profiles SET is_public = false WHERE user_id = %s", (A,))
check(run(O, 'publicCheck', id=chk) == [], 'private profile hides its transcripts')
cur.execute("UPDATE profiles SET is_public = true WHERE user_id = %s", (A,))
run(R, 'replaceRoles', 3, expect_error='ForeignKeyViolation', r=uuid.uuid4(), s=17, w=1.0, m=True, l='DEFENDED')  # DEFENDED level accepted by the check

# insights (V15): commit rhythm, AI-history details, goals
days = '{3,4,14,4,3,1,1}'
hours = '{' + ','.join(['1'] * 23 + ['7']) + '}'
check(run(P, 'setRhythm', first=now - dt.timedelta(days=200), days=days, hours=hours, id=repoA) == 1, 'rhythm saved')
run(P, 'setRhythm', expect_error='CheckViolation', first=now, days='{1,2,3}', hours=hours, id=repoA)
row = [r for r in run(P, 'evidenceFor', 2, users=[A]) if r[0] == repoA][0]
check(row[13] == [3, 4, 14, 4, 3, 1, 1] and len(row[14]) == 24 and row[14][23] == 7 and row[12] is not None,
      f'rhythm read back as arrays: {row[12:15]}')
check(run(P, 'setChatMeta', source='MEMORY', mode='LEARNING', c=0, note='working through a course', id=chatA) == 1, 'chat meta saved')
run(P, 'setChatMeta', expect_error='CheckViolation', source='TWITTER', mode='USED', c=0, note=None, id=chatA)
run(P, 'setChatMeta', expect_error='CheckViolation', source='MEMORY', mode='MASTERED', c=0, note=None, id=chatA)
run(P, 'setChatMeta', expect_error='CheckViolation', source='EXPORT', mode='ASKED', c=-1, note=None, id=chatA)
run(P, 'setChatMeta', expect_error='CheckViolation', source='EXPORT', mode='ASKED', c=1, note='x' * 201, id=chatA)
row = [r for r in run(P, 'evidenceFor', 2, users=[A]) if r[0] == chatA][0]
check(row[15:19] == ('MEMORY', 'LEARNING', 0, 'working through a course') or list(row[15:19]) == ['MEMORY', 'LEARNING', 0, 'working through a course'],
      f'chat meta read back: {row[15:19]}')
exp = uuid.uuid4()
run(P, 'insertEvidence', id=exp, u=A, kind='CHAT_EXPORT', title='Discussed Docker', url=None, commits=0, weeks=0, **ev)  # no source: legacy
check(run(P, 'deleteChatEvidence', 1, u=B, source='MEMORY') == 0, 'cannot delete another user\'s memory import')
check(run(P, 'deleteChatEvidence', 1, u=A, source='EXPORT') == 1, 'export delete takes legacy rows, not memory ones')
check(len([r for r in run(P, 'evidenceFor', 2, users=[A]) if r[0] == chatA]) == 1, 'memory evidence kept')
check(run(P, 'projectTitles', u=A) == [('fest analytics',)], f'project titles lower-cased: {run(P, "projectTitles", u=A)}')
check(run(P, 'goal', u=A) == [], 'no goal yet')
run(P, 'setGoal', u=A, goal='Data analyst at a product company')
run(P, 'setGoal', u=A, goal='ML engineer')   # update path
check(run(P, 'goal', u=A) == [('ML engineer',)], 'goal updated')
check(run(P, 'goal', u=B) == [], 'goals are per user')
run(P, 'setGoal', expect_error='CheckViolation', u=B, goal='x' * 81)

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
