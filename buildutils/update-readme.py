#!/usr/bin/env python3

import re
import os
from os.path import join
import urllib.request
from urllib.parse import urlencode

def prettySQL(raw):
    # return raw
    # curl https://www.freeformatter.com/sql-formatter.html \
    #  -d 'forceInNewWindow=true' \
    #  -d 'sqlKeywordsCase=UPPER_CASE' \
    #  -d 'indentation=TWO_SPACES' \
    #  -d 'sqlString=select count(*) from foo'
    params = urlencode({'forceInNewWindow': 'true',
                       'sqlKeywordsCase': 'UPPER_CASE',
                       'indentation': 'TWO_SPACES',
                       'sqlString': raw})
    data = params.encode('utf-8')
    with urllib.request.urlopen("https://www.freeformatter.com/sql-formatter.html", data) as f:
        return f.read().decode('utf-8')


impexes = set()
for root, dirs, files in os.walk('resources'):
    for f in files:
        if f.endswith(".impex"):
            impexes.add(join(root, f))

print(impexes)

blocks = []
for impex in impexes:
    with open(impex) as i:
        content = i.read()
        pattern = re.compile(r"@readme(.+?)INSERT_UPDATE", flags=re.DOTALL)
        for match in pattern.finditer(content):
            lines = match.group(0).split('\n')
            header = lines[0]
            types = [f.strip() for f in header[7:].split(',')]
            text = ""
            query = ""
            for l in [l.rstrip() for l in lines[1:-1]]:
                l = re.sub("^# ?", "", l)
                if l.lower().strip().startswith('select'):
                    query += l + '\n'
                    continue
                if len(query) > 0:
                    query += l + '\n'
                else:
                    text += l + '\n'
            query = prettySQL(query)
            blocks.append({'file': impex, 'types': types, 'text': text.strip(), 'query': query.strip()})

blocks.sort(key=lambda entry: "-".join(entry['types']))
print(blocks)

table = "<table>"
table += "<tr><th>Type(s)</th><th>Query</th><th>Notes</th></tr>\n"
for block in blocks:
    table += "<tr>"
    table += f"<td>{','.join(block['types'])}</td>"
    table += f"""<td>
    
```sql
{block['query']}
```

</td><td>

{block['text']}

</td>"""
    table += "</tr>\n"
table += "</table>"

print(table)

newContent = ""
with open('README.md', 'r') as old:
    content = old.read()
    newContent = re.sub(r'(<!-- @queries-start -->).+?(<!-- @queries-end -->)', f"""<!-- @queries-start -->
{table}
<!-- @queries-end -->""", content, flags=re.DOTALL)
    print(newContent)

if newContent:
    with open('README.md', 'w') as new:
        new.write(newContent)




