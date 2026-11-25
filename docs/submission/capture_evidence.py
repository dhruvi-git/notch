"""Captures real API inputs and outputs, including error handling, as demonstration evidence.

Needs the API running locally (local profile):  mvn -pl api spring-boot:run
usage: python docs/submission/capture_evidence.py [http://localhost:8090]
writes docs/submission/evidence/api-transcript.md

Session tokens are redacted. Long lists are cut to their first few items (marked "…").
"""
import json
import sys
import uuid
import urllib.error
import urllib.request
from pathlib import Path

API = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090"
ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).parent / "evidence"
OUT.mkdir(exist_ok=True)
RUN = uuid.uuid4().hex[:6]
log = []


def trim(v, depth=0):
    if isinstance(v, list):
        out = [trim(x, depth + 1) for x in v[:4]]
        return out + [f"… {len(v) - 4} more"] if len(v) > 4 else out
    if isinstance(v, dict):
        return {k: ("ntch_ses_…redacted" if k == "token" else trim(x, depth + 1)) for k, x in v.items()}
    return v


def call(title, method, path, body=None, token=None, raw=None, ctype="application/json", note=None, keep=None):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if raw is not None:
        data, headers["Content-Type"] = raw, ctype
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    try:
        parsed = json.loads(text) if text else None
    except json.JSONDecodeError:
        parsed = text[:400]
    shown = keep(parsed) if keep and isinstance(parsed, (dict, list)) and status < 400 else parsed
    log.append({"title": title, "method": method, "path": path, "body": body,
                "raw": raw is not None, "auth": bool(token), "status": status, "response": trim(shown), "note": note})
    print(f"{status} {method} {path}")
    return status, parsed


def multipart(field, filename, content, ctype="application/json"):
    boundary = "----notch" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {ctype}\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


# ---------------------------------------------------------------- normal flow
call("Health check", "GET", "/actuator/health")
call("Which sign-in options this server offers", "GET", "/auth/config")
_, rec = call("Sign in as a recruiter (local profile test sign-in)", "POST", "/auth/dev-login", {"handle": f"recruiter-{RUN}"})
R = rec["token"]
call("Seed 300 synthetic public candidates (skips those already present)", "POST", "/api/dev/demo-pool?size=300")
call("Skill autocomplete from the radix tree", "GET", "/api/skills/autocomplete?q=py", token=R)


def skill_id(name):
    _, res = call(f"Look up skill id: {name}", "GET", f"/api/skills/autocomplete?q={urllib.request.quote(name)}", token=R)
    items = res if isinstance(res, list) else res.get("skills", res.get("items", []))
    for s in items:
        if s["name"].lower() == name.lower():
            return s["id"]
    return items[0]["id"]


jd = ("Must have strong SQL and Python. Dashboards in Power BI. "
      "Statistics is a plus.")
_, drive = call("Create a drive from a job description: requirements are read from the text", "POST", "/api/drives",
                {"title": "Data analyst", "description": jd}, token=R,
                note="\"Must have\" sentences become must-haves (weight 3, Verified expected); \"a plus\" becomes a nice-to-have (weight 1).")
D = drive["id"]
role = drive["roles"][0]["id"]
call("Ranked shortlist (randomized top-k), with each requirement's share of the score", "GET",
     f"/api/drives/{D}/roles/{role}/shortlist?k=5", token=R)

ids = {n: skill_id(n) for n in ["SQL", "Python", "Power BI", "Machine Learning", "Java", "Spring Boot"]}
roles = [
    {"name": "Data analyst", "seats": 15, "threshold": 50, "requirements": [
        {"skillId": ids["SQL"], "weight": 3, "mustHave": True, "minLevel": "VERIFIED"},
        {"skillId": ids["Python"], "weight": 3, "mustHave": True, "minLevel": "VERIFIED"},
        {"skillId": ids["Power BI"], "weight": 2, "mustHave": False, "minLevel": "SHOWN"}]},
    {"name": "ML engineer", "seats": 10, "threshold": 50, "requirements": [
        {"skillId": ids["Python"], "weight": 3, "mustHave": True, "minLevel": "VERIFIED"},
        {"skillId": ids["Machine Learning"], "weight": 3, "mustHave": True, "minLevel": "SHOWN"}]},
    {"name": "Backend", "seats": 10, "threshold": 50, "requirements": [
        {"skillId": ids["Java"], "weight": 3, "mustHave": True, "minLevel": "VERIFIED"},
        {"skillId": ids["Spring Boot"], "weight": 2, "mustHave": False, "minLevel": "SHOWN"}]},
]
call("Replace the roles: three roles, 35 seats", "PUT", f"/api/drives/{D}/roles", {"roles": roles}, token=R,
     keep=lambda d: {"id": d["id"], "roles": [{"name": r["name"], "seats": r["seats"], "threshold": r["threshold"],
                                                 "requirements": [q.get("skill") for q in r["requirements"]]} for r in d["roles"]]})
_, assignment = call("Assign every seat: min-cost max-flow, with max-flow and best-score-first greedy for comparison",
                     "POST", f"/api/drives/{D}/assign", token=R)

top = None
_, sl = call("Shortlist for the first role again (to pick a public profile)", "GET",
             f"/api/drives/{D}/roles/{json.loads(json.dumps(assignment)).get('roles', [{}])[0].get('roleId', role)}/shortlist?k=1",
             token=R)
if isinstance(sl, dict) and sl.get("candidates"):
    top = sl["candidates"][0]["handle"]
if top:
    call(f"Public profile of @{top}: scores, evidence levels and percentile bands (no sign-in)", "GET",
         f"/public/profiles/{top}",
         keep=lambda p: {k: p[k] for k in p if k in ("handle", "headline", "skills")})

_, stu = call("Sign in as a student", "POST", "/auth/dev-login", {"handle": f"student-{RUN}"})
S = stu["token"]
export = (ROOT / "samples" / "chatgpt-conversations.json").read_bytes()
raw, ct = multipart("file", "chatgpt-conversations.json", export)
call("Upload a ChatGPT export (samples/chatgpt-conversations.json): skills suggested, unapproved", "POST",
     "/api/portfolio/chat-export", token=S, raw=raw, ctype=ct)
_, ev = call("Add a project as evidence: skills are found in its text", "POST", "/api/portfolio/evidence",
             {"title": "Campus canteen dashboard", "url": "https://example.com/canteen",
              "description": "Power BI dashboard over a PostgreSQL database, cleaned with Python and pandas.",
              "skills": ["SQL"], "lastActive": "2026-10-01"}, token=S)
call("The prompt a student pastes into ChatGPT or Claude", "GET", "/api/portfolio/memory-prompt", token=S)
reply = """Sure! Here's what I remember:

**NOTCH PROFILE**
**SKILLS**
- Python | USED | built a pandas pipeline to clean canteen sales data
- SQL | USED | wrote window-function queries for a sales dashboard
- Power BI | LEARNING | asked how to build measures and slicers
- Kubernetes | ASKED | asked what a pod is
- Quantum Basket Weaving | USED | made up, to show unrecognised names are reported
PROJECTS
- Canteen sales dashboard | monthly revenue per stall | Python, SQL, Power BI
GOAL
- Data analyst
END"""
call("Import the assistant's reply (markdown and bold kept as an assistant would send it)", "POST",
     "/api/portfolio/memory-import", {"text": reply}, token=S,
     note="Skills are matched against the catalogue and added unapproved; names it does not know are reported, not guessed.")
call("The student's portfolio: score and evidence level per skill", "GET", "/api/portfolio/me", token=S,
     keep=lambda p: {"skills": p.get("skills"), "evidenceCount": len(p.get("evidence", []))})
call("The student's read: archetype, trait and stats", "GET", "/api/insights/me", token=S,
     keep=lambda r: {"goal": r.get("goal"), **{k: (r.get("read") or {}).get(k) for k in ("archetype", "stats", "insights")}})

# ---------------------------------------------------------------- error handling
call("ERROR: no token at all (local profile)", "GET", "/api/drives",
     note="The local profile deliberately falls back to a built-in development user when no token is sent "
          "(`notch.auth.dev-fallback: true` in application-local.yml). The default and cloud profiles set it to "
          "false, so the same request gets 401 there, exactly like the invalid token below.")
call("ERROR: an invalid or revoked token", "GET", "/api/drives", token="ntch_ses_not-a-real-token")
call("ERROR: a drive without a job description", "POST", "/api/drives", {"title": "Empty", "description": "  "}, token=R)
call("ERROR: malformed JSON", "POST", "/api/drives", raw=b"{\"title\": \"oops\"", token=R)
call("ERROR: a drive that does not exist", "GET", f"/api/drives/{uuid.uuid4()}", token=R)
call("ERROR: another user's drive (cross-user isolation: 404, not 403, so ids leak nothing)", "GET",
     f"/api/drives/{D}", token=S)
call("ERROR: a threshold outside 0-100", "PUT", f"/api/drives/{D}/roles",
     {"roles": [{"name": "Bad", "seats": 1, "threshold": 150, "requirements": roles[0]["requirements"]}]}, token=R)
call("ERROR: a negative number of seats", "PUT", f"/api/drives/{D}/roles",
     {"roles": [{"name": "Bad", "seats": -1, "threshold": 50, "requirements": roles[0]["requirements"]}]}, token=R)
call("ERROR: a path id that is not a UUID", "GET", "/api/drives/not-a-uuid", token=R)
if isinstance(ev, dict) and ev.get("id"):
    eid = ev["id"]
elif isinstance(ev, dict):
    eid = next((e["id"] for e in ev.get("evidence", []) if e.get("kind") == "PROJECT"), None)
else:
    eid = None
if eid:
    call("ERROR: an Ownership Check on a project, not a repository", "POST", "/api/ownership/checks",
         {"evidenceId": eid}, token=S)
raw, ct = multipart("file", "notes.json", b"this is not a chat export")
call("ERROR: a chat export that is not one", "POST", "/api/portfolio/chat-export", token=S, raw=raw, ctype=ct,
     note="Handled gracefully rather than rejected: the file sniffer finds no ChatGPT or Claude structure, reads it as "
          "plain text, and suggests nothing.")
call("ERROR: a role with no required skills", "PUT", f"/api/drives/{D}/roles",
     {"roles": [{"name": "Empty role", "seats": 2, "threshold": 50, "requirements": []}]}, token=R)
call("ERROR: a public profile that does not exist", "GET", "/public/profiles/no-such-person-xyz")

# ---------------------------------------------------------------- write
lines = ["# API evidence: real requests and responses", "",
         f"Captured from the Notch API running locally (`{API}`, local profile, no AI keys) by "
         "[`capture_evidence.py`](../capture_evidence.py). Session tokens are redacted; long lists show their "
         "first four items.", ""]
section = None
for i, e in enumerate(log, 1):
    s = "Error handling" if e["title"].startswith("ERROR") else "Normal use"
    if s != section:
        section = s
        lines += [f"## {s}", ""]
    title = e["title"].removeprefix("ERROR: ")
    lines += [f"### {i}. {title}", "", f"`{e['method']} {e['path']}`" + (" · signed in" if e["auth"] else "") +
              f" → **{e['status']}**", ""]
    if e["note"]:
        lines += [e["note"], ""]
    if e["body"] is not None:
        lines += ["Request:", "", "```json", json.dumps(e["body"], indent=2, ensure_ascii=False), "```", ""]
    elif e["raw"]:
        lines += ["Request: a multipart upload or raw body (see the script).", ""]
    if e["response"] is not None:
        lines += ["Response:", "", "```json", json.dumps(e["response"], indent=2, ensure_ascii=False)[:3500], "```", ""]
(OUT / "api-transcript.md").write_text("\n".join(lines), encoding="utf-8")
print("wrote", OUT / "api-transcript.md")
