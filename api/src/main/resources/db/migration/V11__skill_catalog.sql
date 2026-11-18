-- The starting skill catalogue for tech students and freshers. Ids are stable: add new skills
-- with new ids in a later migration, never renumber.
-- Single-letter and common-word languages (C, R, Go) carry a qualifier in their name so that
-- ordinary text ("plan C", "R&D", "go live") is not read as a skill.

INSERT INTO skills (id, name, domain) VALUES
    (1, 'Python', 'Languages'), (2, 'Java', 'Languages'), (3, 'JavaScript', 'Languages'),
    (4, 'TypeScript', 'Languages'), (5, 'C++', 'Languages'), (6, 'C (language)', 'Languages'),
    (7, 'Kotlin', 'Languages'), (8, 'Swift', 'Languages'), (9, 'Rust', 'Languages'),
    (10, 'Go (Golang)', 'Languages'), (11, 'C#', 'Languages'), (12, 'R (language)', 'Languages'),
    (13, 'PHP', 'Languages'), (14, 'Ruby', 'Languages'), (15, 'Dart', 'Languages'),
    (16, 'Scala', 'Languages'), (17, 'SQL', 'Data'),

    (20, 'pandas', 'Data'), (21, 'NumPy', 'Data'), (22, 'Excel', 'Data'), (23, 'Power BI', 'Data'),
    (24, 'Tableau', 'Data'), (25, 'Statistics', 'Data'), (26, 'Data Visualization', 'Data'),
    (27, 'Matplotlib', 'Data'), (28, 'Seaborn', 'Data'), (29, 'Apache Spark', 'Data'), (30, 'ETL', 'Data'),
    (31, 'Data Cleaning', 'Data'), (32, 'A/B Testing', 'Data'), (33, 'Jupyter', 'Data'),
    (34, 'Looker Studio', 'Data'), (35, 'dbt', 'Data'), (36, 'Airflow', 'Data'),

    (40, 'Machine Learning', 'AI & ML'), (41, 'Deep Learning', 'AI & ML'), (42, 'scikit-learn', 'AI & ML'),
    (43, 'TensorFlow', 'AI & ML'), (44, 'PyTorch', 'AI & ML'), (45, 'Keras', 'AI & ML'),
    (46, 'NLP', 'AI & ML'), (47, 'Computer Vision', 'AI & ML'), (48, 'OpenCV', 'AI & ML'),
    (49, 'LLMs', 'AI & ML'), (50, 'Prompt Engineering', 'AI & ML'), (51, 'LangChain', 'AI & ML'),
    (52, 'Hugging Face', 'AI & ML'), (53, 'Time Series', 'AI & ML'), (54, 'Reinforcement Learning', 'AI & ML'),

    (60, 'React', 'Web'), (61, 'Next.js', 'Web'), (62, 'Vue', 'Web'), (63, 'Angular', 'Web'),
    (64, 'Node.js', 'Web'), (65, 'Express.js', 'Web'), (66, 'HTML', 'Web'), (67, 'CSS', 'Web'),
    (68, 'Tailwind CSS', 'Web'), (69, 'Django', 'Web'), (70, 'Flask', 'Web'), (71, 'FastAPI', 'Web'),
    (72, 'Spring Boot', 'Web'), (73, 'REST APIs', 'Web'), (74, 'GraphQL', 'Web'), (75, 'Redux', 'Web'),

    (80, 'Android', 'Mobile'), (81, 'iOS', 'Mobile'), (82, 'Flutter', 'Mobile'), (83, 'React Native', 'Mobile'),

    (90, 'PostgreSQL', 'Databases'), (91, 'MySQL', 'Databases'), (92, 'MongoDB', 'Databases'),
    (93, 'Redis', 'Databases'), (94, 'SQLite', 'Databases'), (95, 'Firebase', 'Databases'),
    (96, 'Elasticsearch', 'Databases'),

    (100, 'Git', 'DevOps & Cloud'), (101, 'GitHub Actions', 'DevOps & Cloud'), (102, 'Docker', 'DevOps & Cloud'),
    (103, 'Kubernetes', 'DevOps & Cloud'), (104, 'AWS', 'DevOps & Cloud'), (105, 'Google Cloud', 'DevOps & Cloud'),
    (106, 'Azure', 'DevOps & Cloud'), (107, 'Linux', 'DevOps & Cloud'), (108, 'CI/CD', 'DevOps & Cloud'),
    (109, 'Terraform', 'DevOps & Cloud'), (110, 'Bash', 'DevOps & Cloud'),

    (120, 'Data Structures', 'CS Fundamentals'), (121, 'Algorithms', 'CS Fundamentals'),
    (122, 'System Design', 'CS Fundamentals'), (123, 'Object-Oriented Programming', 'CS Fundamentals'),
    (124, 'Unit Testing', 'CS Fundamentals'),

    (130, 'Figma', 'Design'), (131, 'UI/UX Design', 'Design'),
    (133, 'Blockchain', 'Other'), (134, 'Solidity', 'Other');

INSERT INTO skill_aliases (alias, skill_id) VALUES
    ('py', 1), ('python3', 1), ('js', 3), ('ecmascript', 3), ('ts', 4), ('cpp', 5),
    ('c programming', 6), ('golang', 10), ('csharp', 11), ('c sharp', 11),
    ('rstudio', 12), ('r programming', 12), ('structured query language', 17),
    ('pandas df', 20), ('numpy arrays', 21), ('ms excel', 22), ('microsoft excel', 22), ('spreadsheets', 22),
    ('powerbi', 23), ('statistical analysis', 25), ('data viz', 26), ('dataviz', 26),
    ('spark', 29), ('pyspark', 29), ('data wrangling', 31), ('ab testing', 32), ('a b testing', 32),
    ('jupyter notebook', 33), ('google data studio', 34), ('apache airflow', 36),
    ('ml', 40), ('dl', 41), ('sklearn', 42), ('scikit learn', 42), ('torch', 44),
    ('natural language processing', 46), ('large language models', 49), ('llm', 49),
    ('generative ai', 49), ('genai', 49), ('huggingface', 52), ('transformers', 52),
    ('time series forecasting', 53), ('forecasting', 53), ('rl', 54),
    ('react.js', 60), ('reactjs', 60), ('nextjs', 61), ('vue.js', 62), ('vuejs', 62), ('angularjs', 63),
    ('nodejs', 64), ('node js', 64), ('expressjs', 65), ('html5', 66), ('css3', 67),
    ('tailwind', 68), ('tailwindcss', 68), ('springboot', 72), ('rest api', 73), ('restful', 73),
    ('restful apis', 73), ('ios development', 81), ('react-native', 83),
    ('postgres', 90), ('mongo', 92), ('elastic search', 96),
    ('github workflows', 101), ('k8s', 103), ('amazon web services', 104), ('gcp', 105),
    ('google cloud platform', 105), ('microsoft azure', 106), ('ci cd', 108),
    ('continuous integration', 108), ('shell scripting', 110), ('shell', 110),
    ('dsa', 120), ('oop', 123), ('object oriented programming', 123),
    ('jest', 124), ('junit', 124), ('pytest', 124), ('ui ux', 131), ('ux design', 131),
    ('ui design', 131), ('user experience', 131), ('web3', 133);
