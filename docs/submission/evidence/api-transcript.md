# API evidence: real requests and responses

Captured from the Notch API running locally (`http://localhost:8090`, local profile, no AI keys) by [`capture_evidence.py`](../capture_evidence.py). Session tokens are redacted; long lists show their first four items.

## Normal use

### 1. Health check

`GET /actuator/health` → **200**

Response:

```json
{
  "groups": [
    "liveness",
    "readiness"
  ],
  "status": "UP"
}
```

### 2. Which sign-in options this server offers

`GET /auth/config` → **200**

Response:

```json
{
  "devLogin": true,
  "github": false,
  "google": false
}
```

### 3. Sign in as a recruiter (local profile test sign-in)

`POST /auth/dev-login` → **200**

Request:

```json
{
  "handle": "recruiter-6f9df7"
}
```

Response:

```json
{
  "token": "ntch_ses_…redacted",
  "expiresAt": "2026-12-25T15:03:23.932732Z",
  "user": {
    "id": "5cd67c94-9fc5-4392-8d61-a5514b68ab06",
    "handle": "recruiter-6f9df7",
    "avatarUrl": null,
    "github": false
  }
}
```

### 4. Seed 300 synthetic public candidates (skips those already present)

`POST /api/dev/demo-pool?size=300` → **200**

Response:

```json
{
  "created": 0,
  "skipped": 300
}
```

### 5. Skill autocomplete from the radix tree

`GET /api/skills/autocomplete?q=py` · signed in → **200**

Response:

```json
[
  {
    "id": 1,
    "name": "Python"
  },
  {
    "id": 44,
    "name": "PyTorch"
  },
  {
    "id": 29,
    "name": "Apache Spark"
  },
  {
    "id": 124,
    "name": "Unit Testing"
  }
]
```

### 6. Create a drive from a job description: requirements are read from the text

`POST /api/drives` · signed in → **201**

"Must have" sentences become must-haves (weight 3, Verified expected); "a plus" becomes a nice-to-have (weight 1).

Request:

```json
{
  "title": "Data analyst",
  "description": "Must have strong SQL and Python. Dashboards in Power BI. Statistics is a plus."
}
```

Response:

```json
{
  "id": "1e8d8fee-0f0c-49d9-9009-8bb94284d535",
  "title": "Data analyst",
  "description": "Must have strong SQL and Python. Dashboards in Power BI. Statistics is a plus.",
  "createdAt": "2026-11-25T15:03:25.472237Z",
  "roles": [
    {
      "id": "6aa24e38-00f7-47fe-9068-d2d95d224b24",
      "name": "Data analyst",
      "seats": 1,
      "threshold": 50.0,
      "requirements": [
        {
          "skillId": 1,
          "skill": "Python",
          "weight": 3.0,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 17,
          "skill": "SQL",
          "weight": 3.0,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 23,
          "skill": "Power BI",
          "weight": 2.0,
          "mustHave": false,
          "minLevel": "SHOWN"
        },
        {
          "skillId": 25,
          "skill": "Statistics",
          "weight": 1.0,
          "mustHave": false,
          "minLevel": "SHOWN"
        }
      ]
    }
  ]
}
```

### 7. Ranked shortlist (randomized top-k), with each requirement's share of the score

`GET /api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles/6aa24e38-00f7-47fe-9068-d2d95d224b24/shortlist?k=5` · signed in → **200**

Response:

```json
{
  "roleId": "6aa24e38-00f7-47fe-9068-d2d95d224b24",
  "role": "Data analyst",
  "poolSize": 301,
  "eligible": 65,
  "threshold": 50.0,
  "candidates": [
    {
      "userId": "24b6abad-1bde-4c45-86f7-467f112710c6",
      "handle": "demo-0154",
      "headline": "Frontend developer, final-year B.Tech",
      "score": 59.6,
      "breakdown": [
        {
          "skill": "Python",
          "weight": 3.0,
          "skillScore": 45.1,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 15.03
        },
        {
          "skill": "SQL",
          "weight": 3.0,
          "skillScore": 80.3,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 26.77
        },
        {
          "skill": "Power BI",
          "weight": 2.0,
          "skillScore": 53.6,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 11.91
        },
        {
          "skill": "Statistics",
          "weight": 1.0,
          "skillScore": 53.0,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 5.89
        }
      ],
      "fix": null
    },
    {
      "userId": "f4f1bd22-b8b1-4402-b29b-71065ecbe675",
      "handle": "demo-0224",
      "headline": "Full-stack and data, final-year B.Tech",
      "score": 52.43,
      "breakdown": [
        {
          "skill": "Python",
          "weight": 3.0,
          "skillScore": 72.6,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 24.2
        },
        {
          "skill": "SQL",
          "weight": 3.0,
          "skillScore": 84.7,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 28.23
        },
        {
          "skill": "Power BI",
          "weight": 2.0,
          "skillScore": 0.0,
          "level": null,
          "levelFactor": 0.0,
          "contribution": 0.0
        },
        {
          "skill": "Statistics",
          "weight": 1.0,
          "skillScore": 0.0,
          "level": null,
          "levelFactor": 0.0,
          "contribution": 0.0
        }
      ],
      "fix": null
    },
    {
      "userId": "b16408ee-1899-45dd-a1ef-130d6b7c41c9",
      "handle": "demo-0229",
      "headline": "ML engineer, final-year B.Tech",
      "score": 51.68,
      "breakdown": [
        {
          "skill": "Python",
          "weight": 3.0,
          "skillScore": 60.0,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 20.0
        },
        {
          "skill": "SQL",
          "weight": 3.0,
          "skillScore": 42.1,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 14.03
        },
        {
          "skill": "Power BI",
          "weight": 2.0,
          "skillScore": 42.1,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 9.36
        },
        {
          "skill": "Statistics",
          "weight": 1.0,
          "skillScore": 74.6,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 8.29
        }
      ],
      "fix": null
    },
    {
      "userId": "3ffe221d-9987-4194-ae2a-e6fbfc776c07",
      "handle": "demo-0058",
      "headline": "Backend developer, final-year B.Tech",
      "score": 50.16,
      "breakdown": [
        {
          "skill": "Python",
          "weight": 3.0,
          "skillScore": 37.6,
          "level": "VERIF
```

### 8. Look up skill id: SQL

`GET /api/skills/autocomplete?q=SQL` · signed in → **200**

Response:

```json
[
  {
    "id": 17,
    "name": "SQL"
  },
  {
    "id": 94,
    "name": "SQLite"
  }
]
```

### 9. Look up skill id: Python

`GET /api/skills/autocomplete?q=Python` · signed in → **200**

Response:

```json
[
  {
    "id": 1,
    "name": "Python"
  }
]
```

### 10. Look up skill id: Power BI

`GET /api/skills/autocomplete?q=Power%20BI` · signed in → **200**

Response:

```json
[
  {
    "id": 23,
    "name": "Power BI"
  }
]
```

### 11. Look up skill id: Machine Learning

`GET /api/skills/autocomplete?q=Machine%20Learning` · signed in → **200**

Response:

```json
[
  {
    "id": 40,
    "name": "Machine Learning"
  }
]
```

### 12. Look up skill id: Java

`GET /api/skills/autocomplete?q=Java` · signed in → **200**

Response:

```json
[
  {
    "id": 2,
    "name": "Java"
  },
  {
    "id": 3,
    "name": "JavaScript"
  }
]
```

### 13. Look up skill id: Spring Boot

`GET /api/skills/autocomplete?q=Spring%20Boot` · signed in → **200**

Response:

```json
[
  {
    "id": 72,
    "name": "Spring Boot"
  }
]
```

### 14. Replace the roles: three roles, 35 seats

`PUT /api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles` · signed in → **200**

Request:

```json
{
  "roles": [
    {
      "name": "Data analyst",
      "seats": 15,
      "threshold": 50,
      "requirements": [
        {
          "skillId": 17,
          "weight": 3,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 1,
          "weight": 3,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 23,
          "weight": 2,
          "mustHave": false,
          "minLevel": "SHOWN"
        }
      ]
    },
    {
      "name": "ML engineer",
      "seats": 10,
      "threshold": 50,
      "requirements": [
        {
          "skillId": 1,
          "weight": 3,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 40,
          "weight": 3,
          "mustHave": true,
          "minLevel": "SHOWN"
        }
      ]
    },
    {
      "name": "Backend",
      "seats": 10,
      "threshold": 50,
      "requirements": [
        {
          "skillId": 2,
          "weight": 3,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 72,
          "weight": 2,
          "mustHave": false,
          "minLevel": "SHOWN"
        }
      ]
    }
  ]
}
```

Response:

```json
{
  "id": "1e8d8fee-0f0c-49d9-9009-8bb94284d535",
  "roles": [
    {
      "name": "Data analyst",
      "seats": 15,
      "threshold": 50.0,
      "requirements": [
        "Python",
        "SQL",
        "Power BI"
      ]
    },
    {
      "name": "ML engineer",
      "seats": 10,
      "threshold": 50.0,
      "requirements": [
        "Python",
        "Machine Learning"
      ]
    },
    {
      "name": "Backend",
      "seats": 10,
      "threshold": 50.0,
      "requirements": [
        "Java",
        "Spring Boot"
      ]
    }
  ]
}
```

### 15. Assign every seat: min-cost max-flow, with max-flow and best-score-first greedy for comparison

`POST /api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/assign` · signed in → **200**

Response:

```json
{
  "runAt": "2026-11-25T15:03:25.824777400Z",
  "totalSeats": 35,
  "filled": 24,
  "totalMatch": 1414.22,
  "baselineFilled": 22,
  "baselineMatch": 1329.2,
  "roles": [
    {
      "roleId": "f6370572-ee9c-44b6-92ef-a3eaa5cda032",
      "name": "Data analyst",
      "seats": 15,
      "filled": 7,
      "eligible": 8,
      "placements": [
        {
          "userId": "24b6abad-1bde-4c45-86f7-467f112710c6",
          "handle": "demo-0154",
          "score": 60.43,
          "moved": false
        },
        {
          "userId": "f4f1bd22-b8b1-4402-b29b-71065ecbe675",
          "handle": "demo-0224",
          "score": 58.99,
          "moved": false
        },
        {
          "userId": "68103253-1bf8-4005-834c-0048f9b3b272",
          "handle": "demo-0038",
          "score": 51.64,
          "moved": true
        },
        {
          "userId": "1041f7d8-4b4e-4a3e-aace-034f47e6293f",
          "handle": "demo-0177",
          "score": 51.0,
          "moved": false
        },
        "… 3 more"
      ]
    },
    {
      "roleId": "d1780984-e985-4b82-8e41-e28f61780ca7",
      "name": "ML engineer",
      "seats": 10,
      "filled": 10,
      "eligible": 12,
      "placements": [
        {
          "userId": "e7dcac08-2ced-4d8e-a6c3-91b15fc49daf",
          "handle": "demo-0287",
          "score": 84.0,
          "moved": false
        },
        {
          "userId": "344d450c-b438-4c15-8ed5-dd1cfd5df4e6",
          "handle": "demo-0181",
          "score": 69.0,
          "moved": false
        },
        {
          "userId": "36b7b7db-7dc8-40ad-b7d1-a08dff61ba31",
          "handle": "demo-0056",
          "score": 58.6,
          "moved": false
        },
        {
          "userId": "3c58e246-0092-4655-a588-54ec1ca229f6",
          "handle": "demo-0215",
          "score": 58.4,
          "moved": false
        },
        "… 6 more"
      ]
    },
    {
      "roleId": "3a2f51f2-3088-42fd-ac6a-4ec466b5e83b",
      "name": "Backend",
      "seats": 10,
      "filled": 7,
      "eligible": 7,
      "placements": [
        {
          "userId": "545aa570-3326-4218-a8de-c358716fe031",
          "handle": "demo-0197",
          "score": 72.34,
          "moved": false
        },
        {
          "userId": "e1b52d12-140a-458c-8cc4-3454cc80c47f",
          "handle": "demo-0143",
          "score": 65.0,
          "moved": false
        },
        {
          "userId": "40cb3752-76a1-4be7-8c84-71f43f24c2bc",
          "handle": "demo-0028",
          "score": 64.66,
          "moved": false
        },
        {
          "userId": "7e38bf03-54cd-4622-a866-ce845e076321",
          "handle": "demo-0036",
          "score": 62.58,
          "moved": false
        },
        "… 3 more"
      ]
    }
  ]
}
```

### 16. Shortlist for the first role again (to pick a public profile)

`GET /api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles/f6370572-ee9c-44b6-92ef-a3eaa5cda032/shortlist?k=1` · signed in → **200**

Response:

```json
{
  "roleId": "f6370572-ee9c-44b6-92ef-a3eaa5cda032",
  "role": "Data analyst",
  "poolSize": 301,
  "eligible": 65,
  "threshold": 50.0,
  "candidates": [
    {
      "userId": "24b6abad-1bde-4c45-86f7-467f112710c6",
      "handle": "demo-0154",
      "headline": "Frontend developer, final-year B.Tech",
      "score": 60.43,
      "breakdown": [
        {
          "skill": "Python",
          "weight": 3.0,
          "skillScore": 45.1,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 16.91
        },
        {
          "skill": "SQL",
          "weight": 3.0,
          "skillScore": 80.3,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 30.11
        },
        {
          "skill": "Power BI",
          "weight": 2.0,
          "skillScore": 53.6,
          "level": "VERIFIED",
          "levelFactor": 1.0,
          "contribution": 13.4
        }
      ],
      "fix": null
    }
  ],
  "nearMisses": [
    {
      "userId": "b1c843b2-4676-42bf-9d6d-05651e919ed6",
      "handle": "demo-0273",
      "headline": "Full-stack and data, final-year B.Tech",
      "fix": {
        "skill": "Python",
        "from": 0.0,
        "to": 14.0,
        "level": "VERIFIED",
        "levelUp": true
      }
    },
    {
      "userId": "952441ea-8d58-4432-9418-dcda345427c6",
      "handle": "demo-0129",
      "headline": "Frontend developer, final-year B.Tech",
      "fix": {
        "skill": "SQL",
        "from": 0.0,
        "to": 16.0,
        "level": "VERIFIED",
        "levelUp": true
      }
    },
    {
      "userId": "f5c6fa4b-8106-478b-a1fa-89e7ccd58d31",
      "handle": "demo-0231",
      "headline": "ML engineer, final-year B.Tech",
      "fix": {
        "skill": "SQL",
        "from": 0.0,
        "to": 17.0,
        "level": "VERIFIED",
        "levelUp": true
      }
    },
    {
      "userId": "e15b3c3d-e3e3-4f83-a2da-0a2633d3d82d",
      "handle": "demo-0003",
      "headline": "Backend developer, final-year B.Tech",
      "fix": {
        "skill": "SQL",
        "from": 0.0,
        "to": 19.0,
        "level": "VERIFIED",
        "levelUp": true
      }
    },
    "… 1 more"
  ]
}
```

### 17. Public profile of @demo-0154: scores, evidence levels and percentile bands (no sign-in)

`GET /public/profiles/demo-0154` → **200**

Response:

```json
{
  "handle": "demo-0154",
  "headline": "Frontend developer, final-year B.Tech",
  "skills": [
    {
      "id": 17,
      "name": "SQL",
      "domain": "Data",
      "score": 80.3,
      "level": "VERIFIED",
      "topPercent": 5,
      "evidenceIds": [
        "871ae493-74ba-4cd1-a6eb-e57604363d68",
        "7228797b-eb52-4c96-acff-e6743d0da895",
        "7cc38057-ccbf-4cec-a660-1a5d5621bf44",
        "2d925bd4-c15d-4fa7-a9a1-1e02f4f347c3"
      ]
    },
    {
      "id": 23,
      "name": "Power BI",
      "domain": "Data",
      "score": 53.6,
      "level": "VERIFIED",
      "topPercent": 25,
      "evidenceIds": [
        "7cc38057-ccbf-4cec-a660-1a5d5621bf44",
        "cf2eeefd-12c8-4af1-b908-7bd437ce2c62"
      ]
    },
    {
      "id": 25,
      "name": "Statistics",
      "domain": "Data",
      "score": 53.0,
      "level": "VERIFIED",
      "topPercent": null,
      "evidenceIds": [
        "7228797b-eb52-4c96-acff-e6743d0da895",
        "2d925bd4-c15d-4fa7-a9a1-1e02f4f347c3"
      ]
    },
    {
      "id": 1,
      "name": "Python",
      "domain": "Languages",
      "score": 45.1,
      "level": "VERIFIED",
      "topPercent": null,
      "evidenceIds": [
        "7cc38057-ccbf-4cec-a660-1a5d5621bf44"
      ]
    },
    "… 3 more"
  ]
}
```

### 18. Sign in as a student

`POST /auth/dev-login` → **200**

Request:

```json
{
  "handle": "student-6f9df7"
}
```

Response:

```json
{
  "token": "ntch_ses_…redacted",
  "expiresAt": "2026-12-25T15:03:25.940904300Z",
  "user": {
    "id": "39d0782c-09ec-4a47-af66-86d76f49a509",
    "handle": "student-6f9df7",
    "avatarUrl": null,
    "github": false
  }
}
```

### 19. Upload a ChatGPT export (samples/chatgpt-conversations.json): skills suggested, unapproved

`POST /api/portfolio/chat-export` · signed in → **200**

Request: a multipart upload or raw body (see the script).

Response:

```json
{
  "format": "chatgpt",
  "conversations": 2,
  "suggestions": []
}
```

### 20. Add a project as evidence: skills are found in its text

`POST /api/portfolio/evidence` · signed in → **201**

Request:

```json
{
  "title": "Campus canteen dashboard",
  "url": "https://example.com/canteen",
  "description": "Power BI dashboard over a PostgreSQL database, cleaned with Python and pandas.",
  "skills": [
    "SQL"
  ],
  "lastActive": "2026-10-01"
}
```

Response:

```json
{
  "id": "cc66adf6-373a-4e71-8c70-7c648c6a3669",
  "kind": "PROJECT",
  "title": "Campus canteen dashboard",
  "url": "https://example.com/canteen",
  "description": "Power BI dashboard over a PostgreSQL database, cleaned with Python and pandas.",
  "ownCommits": 0,
  "activeWeeks": 0,
  "fork": false,
  "duplicate": false,
  "lastActive": "2026-10-01T00:00:00Z",
  "skills": [
    {
      "id": 17,
      "name": "SQL"
    },
    {
      "id": 23,
      "name": "Power BI"
    },
    {
      "id": 90,
      "name": "PostgreSQL"
    },
    {
      "id": 1,
      "name": "Python"
    },
    "… 1 more"
  ],
  "pendingSkills": [],
  "ownership": null
}
```

### 21. The prompt a student pastes into ChatGPT or Claude

`GET /api/portfolio/memory-prompt` · signed in → **200**

Response:

```json
{
  "prompt": "I'm building my profile on Notch, a portfolio for students. Using only what you remember from our past conversations, fill in the template below. Don't guess or invent anything: if you don't know, leave that section empty. Keep my wording where you can.\n\nFor each skill, choose one word: USED (I built something with it), LEARNING (I'm studying it), or ASKED (I asked you about it). Add one short line saying what you remember.\n\nReply with the template only, exactly in this format:\n\nNOTCH PROFILE\nSKILLS\n- skill name | USED or LEARNING or ASKED | what you remember, in one line\nPROJECTS\n- project name | what it does, in one line | skills used, separated by commas\nGOAL\n- the role or job I'm aiming for, if I've told you\nEND"
}
```

### 22. Import the assistant's reply (markdown and bold kept as an assistant would send it)

`POST /api/portfolio/memory-import` · signed in → **200**

Skills are matched against the catalogue and added unapproved; names it does not know are reported, not guessed.

Request:

```json
{
  "text": "Sure! Here's what I remember:\n\n**NOTCH PROFILE**\n**SKILLS**\n- Python | USED | built a pandas pipeline to clean canteen sales data\n- SQL | USED | wrote window-function queries for a sales dashboard\n- Power BI | LEARNING | asked how to build measures and slicers\n- Kubernetes | ASKED | asked what a pod is\n- Quantum Basket Weaving | USED | made up, to show unrecognised names are reported\nPROJECTS\n- Canteen sales dashboard | monthly revenue per stall | Python, SQL, Power BI\nGOAL\n- Data analyst\nEND"
}
```

Response:

```json
{
  "skills": [
    {
      "evidenceId": "b1b8196c-4f8f-44a6-9354-dfc9cb249265",
      "skillId": 1,
      "name": "Python",
      "mode": "USED",
      "note": "built a pandas pipeline to clean canteen sales data"
    },
    {
      "evidenceId": "64949af9-4e63-4385-9c7b-ad23392ae82e",
      "skillId": 17,
      "name": "SQL",
      "mode": "USED",
      "note": "wrote window-function queries for a sales dashboard"
    },
    {
      "evidenceId": "1f1ed19f-bc9a-46d2-945b-60b0eec4e878",
      "skillId": 23,
      "name": "Power BI",
      "mode": "LEARNING",
      "note": "asked how to build measures and slicers"
    },
    {
      "evidenceId": "05299a35-a5d6-491e-8615-891e3649b7f2",
      "skillId": 103,
      "name": "Kubernetes",
      "mode": "ASKED",
      "note": "asked what a pod is"
    }
  ],
  "projects": [
    {
      "evidenceId": "dcdfe62e-2a7e-44d4-8cc1-fff4de8ecdbd",
      "title": "Canteen sales dashboard",
      "skills": [
        "Python",
        "SQL",
        "Power BI"
      ]
    }
  ],
  "skippedProjects": 0,
  "unrecognised": [
    "Quantum Basket Weaving"
  ],
  "goal": "Data analyst"
}
```

### 23. The student's portfolio: score and evidence level per skill

`GET /api/portfolio/me` · signed in → **200**

Response:

```json
{
  "skills": [
    {
      "id": 90,
      "name": "PostgreSQL",
      "domain": "Databases",
      "score": 36.0,
      "level": "SHOWN",
      "topPercent": null,
      "evidenceIds": [
        "cc66adf6-373a-4e71-8c70-7c648c6a3669"
      ]
    },
    {
      "id": 23,
      "name": "Power BI",
      "domain": "Data",
      "score": 36.0,
      "level": "SHOWN",
      "topPercent": null,
      "evidenceIds": [
        "cc66adf6-373a-4e71-8c70-7c648c6a3669"
      ]
    },
    {
      "id": 1,
      "name": "Python",
      "domain": "Languages",
      "score": 36.0,
      "level": "SHOWN",
      "topPercent": null,
      "evidenceIds": [
        "cc66adf6-373a-4e71-8c70-7c648c6a3669"
      ]
    },
    {
      "id": 17,
      "name": "SQL",
      "domain": "Data",
      "score": 36.0,
      "level": "SHOWN",
      "topPercent": null,
      "evidenceIds": [
        "cc66adf6-373a-4e71-8c70-7c648c6a3669"
      ]
    },
    "… 1 more"
  ],
  "evidenceCount": 6
}
```

### 24. The student's read: archetype, trait and stats

`GET /api/insights/me` · signed in → **200**

Response:

```json
{
  "goal": "Data analyst",
  "archetype": {
    "key": "DATA",
    "name": "The Data Storyteller",
    "line": "You turn messy data into something people can see and act on.",
    "reason": "90% of your proven skill is in data and databases: PostgreSQL, Power BI and SQL.",
    "skills": [
      "PostgreSQL",
      "Power BI",
      "SQL"
    ],
    "trait": null
  },
  "stats": {
    "repositories": 0,
    "commits": 0,
    "activeWeeks": 0,
    "skills": 5,
    "defended": 0,
    "since": "2026-10-01T00:00:00Z"
  },
  "insights": []
}
```

## Error handling

### 25. no token at all (local profile)

`GET /api/drives` → **200**

The local profile deliberately falls back to a built-in development user when no token is sent (`notch.auth.dev-fallback: true` in application-local.yml). The default and cloud profiles set it to false, so the same request gets 401 there, exactly like the invalid token below.

Response:

```json
[]
```

### 26. an invalid or revoked token

`GET /api/drives` · signed in → **401**

Response:

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Your session has expired or the token was revoked. Sign in again.",
  "code": "invalid_token"
}
```

### 27. a drive without a job description

`POST /api/drives` · signed in → **400**

Request:

```json
{
  "title": "Empty",
  "description": "  "
}
```

Response:

```json
{
  "detail": "Invalid request content.",
  "instance": "/api/drives",
  "status": 400,
  "title": "Bad Request"
}
```

### 28. malformed JSON

`POST /api/drives` · signed in → **400**

Request: a multipart upload or raw body (see the script).

Response:

```json
{
  "detail": "Failed to read request",
  "instance": "/api/drives",
  "status": 400,
  "title": "Bad Request"
}
```

### 29. a drive that does not exist

`GET /api/drives/1d662889-35ed-4e57-a720-0fe2f4a17c99` · signed in → **404**

Response:

```json
{
  "detail": "That drive was not found.",
  "instance": "/api/drives/1d662889-35ed-4e57-a720-0fe2f4a17c99",
  "status": 404,
  "title": "Not Found",
  "code": "not_found"
}
```

### 30. another user's drive (cross-user isolation: 404, not 403, so ids leak nothing)

`GET /api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535` · signed in → **404**

Response:

```json
{
  "detail": "That drive was not found.",
  "instance": "/api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535",
  "status": 404,
  "title": "Not Found",
  "code": "not_found"
}
```

### 31. a threshold outside 0-100

`PUT /api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles` · signed in → **400**

Request:

```json
{
  "roles": [
    {
      "name": "Bad",
      "seats": 1,
      "threshold": 150,
      "requirements": [
        {
          "skillId": 17,
          "weight": 3,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 1,
          "weight": 3,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 23,
          "weight": 2,
          "mustHave": false,
          "minLevel": "SHOWN"
        }
      ]
    }
  ]
}
```

Response:

```json
{
  "detail": "Bad: the minimum match must be between 0 and 100.",
  "instance": "/api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles",
  "status": 400,
  "title": "Bad Request",
  "code": "invalid_threshold"
}
```

### 32. a negative number of seats

`PUT /api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles` · signed in → **400**

Request:

```json
{
  "roles": [
    {
      "name": "Bad",
      "seats": -1,
      "threshold": 50,
      "requirements": [
        {
          "skillId": 17,
          "weight": 3,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 1,
          "weight": 3,
          "mustHave": true,
          "minLevel": "VERIFIED"
        },
        {
          "skillId": 23,
          "weight": 2,
          "mustHave": false,
          "minLevel": "SHOWN"
        }
      ]
    }
  ]
}
```

Response:

```json
{
  "detail": "Bad: seats must be between 0 and 10,000.",
  "instance": "/api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles",
  "status": 400,
  "title": "Bad Request",
  "code": "invalid_seats"
}
```

### 33. a path id that is not a UUID

`GET /api/drives/not-a-uuid` · signed in → **400**

Response:

```json
{
  "detail": "Failed to convert 'driveId' with value: 'not-a-uuid'",
  "instance": "/api/drives/not-a-uuid",
  "status": 400,
  "title": "Bad Request"
}
```

### 34. an Ownership Check on a project, not a repository

`POST /api/ownership/checks` · signed in → **400**

Request:

```json
{
  "evidenceId": "cc66adf6-373a-4e71-8c70-7c648c6a3669"
}
```

Response:

```json
{
  "detail": "Ownership Checks are about code: pick one of your GitHub repositories.",
  "instance": "/api/ownership/checks",
  "status": 400,
  "title": "Bad Request",
  "code": "not_a_repository"
}
```

### 35. a chat export that is not one

`POST /api/portfolio/chat-export` · signed in → **200**

Handled gracefully rather than rejected: the file sniffer finds no ChatGPT or Claude structure, reads it as plain text, and suggests nothing.

Request: a multipart upload or raw body (see the script).

Response:

```json
{
  "format": "text",
  "conversations": 1,
  "suggestions": []
}
```

### 36. a role with no required skills

`PUT /api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles` · signed in → **400**

Request:

```json
{
  "roles": [
    {
      "name": "Empty role",
      "seats": 2,
      "threshold": 50,
      "requirements": []
    }
  ]
}
```

Response:

```json
{
  "detail": "Empty role: add between 1 and 15 required skills.",
  "instance": "/api/drives/1e8d8fee-0f0c-49d9-9009-8bb94284d535/roles",
  "status": 400,
  "title": "Bad Request",
  "code": "invalid_requirements"
}
```

### 37. a public profile that does not exist

`GET /public/profiles/no-such-person-xyz` → **404**

Response:

```json
{
  "detail": "A public profile called @no-such-person-xyz was not found.",
  "instance": "/public/profiles/no-such-person-xyz",
  "status": 404,
  "title": "Not Found",
  "code": "not_found"
}
```
