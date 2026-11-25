"""Translate personal exports into timestamped records with source identifiers."""
import csv
import datetime as dt
import hashlib
import io
import json
import re
import zipfile
from pathlib import PurePosixPath

from defusedxml import ElementTree as xml

UTC = dt.timezone.utc
MAX_EXPANDED = 256 * 1024 * 1024


def timestamp(raw):
    value = str(raw).strip()
    if re.fullmatch(r'\d{13}', value):
        return dt.datetime.fromtimestamp(int(value) / 1000, UTC)
    try:
        parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        for pattern in ('%Y-%m-%d %H:%M:%S %z', '%b %d, %Y, %I:%M:%S %p',
                        '%m/%d/%y %H:%M:%S', '%Y/%m/%d %H:%M:%S', '%Y/%m/%d',
                        '%m/%d/%Y %H:%M:%S', '%m/%d/%Y %I:%M:%S %p', '%m/%d/%Y'):
            try:
                parsed = dt.datetime.strptime(value, pattern)
                break
            except ValueError:
                continue
        else:
            raise ValueError('Unrecognized timestamp')
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def record(when, kind, name, value='', unit='', note='', source='', identity=None):
    when = timestamp(when).astimezone(UTC)
    fields = (when, kind, str(name)[:160], str(value)[:160], str(unit)[:64], str(note)[:4000], str(source)[:160])
    digest = hashlib.sha256(json.dumps(identity or fields, default=str, ensure_ascii=False).encode()).hexdigest()
    return (*fields, digest)


def apple(stream, source):
    for _, node in xml.iterparse(stream, events=('end',)):
        a = node.attrib
        if node.tag in ('Record', 'Workout') and a.get('startDate'):
            workout = node.tag == 'Workout'
            key = a.get('workoutActivityType' if workout else 'type', '')
            key = re.sub(r'^HK(?:QuantityTypeIdentifier|CategoryTypeIdentifier|WorkoutActivityType|CharacteristicTypeIdentifier)', '', key)
            title = re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', key)
            origin = a.get('sourceName') or source
            yield record(a['startDate'], 'Workout' if workout else ('Sleep' if 'Sleep' in key else 'Measurement'),
                         title, a.get('duration' if workout else 'value',''), a.get('durationUnit' if workout else 'unit',''),
                         a.get('endDate',''), origin, [node.tag, key, origin, a.get('startDate'), a.get('endDate'), a.get('value')])
        node.clear()


def csv_records(stream, source):
    stream = io.TextIOWrapper(stream, encoding='utf-8-sig', newline='')
    sample = stream.read(8192)
    stream.seek(0)
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=',;\t')
    except csv.Error:
        dialect = csv.excel
    rows = csv.reader(stream, dialect)
    headers = next(rows, [])
    # Samsung exports often start with a metadata line before their header.
    if headers and headers[0].startswith('com.samsung.'):
        headers = next(rows, [])
    headers = [h.strip() for h in headers]
    def normalized(h):
        return re.sub(r'[^a-z0-9]+', ' ', h.lower()).strip()
    keys = [normalized(h) for h in headers]
    for date_key in ('timestamp','date time','datetime','start time','start date','activity date','time of measurement','measurement time','time','date'):
        if date_key in keys:
            index = keys.index(date_key)
            break
    else:
        raise ValueError('CSV needs a date, time, start_time, timestamp or activity date column.')
    for cells in rows:
        if not any(cells):
            continue
        row = dict(zip(keys, cells))
        when = cells[index]
        if 'date' in row and 'time' in row and ':' in row['time'] and '-' not in row['time'] and '/' not in row['time']:
            when = row['date']+' '+row['time']
        if 'activity name' in row and 'activity type' in row:
            yield record(when,'Workout',row['activity name'],row.get('elapsed time',''),'s',row['activity type'],source,['strava',row.get('activity id') or when])
        elif 'metric' in row and 'value' in row:
            yield record(when,row.get('kind') or 'Measurement',row['metric'],row['value'],row.get('unit',''),row.get('note',''),source)
        else:
            for idx, (label, value) in enumerate(zip(headers, cells)):
                if idx == index or not label or not value.strip() or not re.fullmatch(r'[+-]?\d+(?:[.,]\d+)?%?',value.strip()):
                    continue
                if keys[idx] in ('id','user id','activity id','create time','update time','end time','start time','date','time'):
                    continue
                unit = re.search(r'\(([^()]*)\)\s*$|\[([^\[\]]*)\]\s*$', label)
                name = label[:unit.start()].strip() if unit else label
                yield record(when,'Measurement',name,value,(unit.group(1) or unit.group(2)) if unit else '',source=source)


def fitbit(stream, source, filename):
    data = json.load(stream)
    if not isinstance(data,list):
        raise ValueError('Expected a Fitbit JSON array.')
    name = re.sub(r'[-_ ]?\d{4}-\d{2}-\d{2}.*$', '', PurePosixPath(filename).stem)
    for row in data:
        when = row.get('dateTime') or row.get('startTime') or row.get('date')
        if not when:
            continue
        if 'value' in row:
            value = row['value']
            if isinstance(value,dict):
                for metric, number in value.items():
                    if isinstance(number,(float,int)) and metric!='confidence':
                        yield record(when,'Measurement',name+' '+metric,number,'bpm' if metric=='bpm' else '',source=source)
            else:
                yield record(when,'Measurement',name,value,source=source)
        else:
            for metric in ('weight','fat','bmi','duration','minutesAsleep','efficiency','calories'):
                if metric in row:
                    yield record(when,'Sleep' if 'minutesAsleep' in row else 'Measurement',metric,row[metric],source=source)


def parse_file(filename, payload, source):
    stream = io.BytesIO(payload)
    ext = PurePosixPath(filename).suffix.lower()
    if ext=='.zip':
        with zipfile.ZipFile(stream) as archive:
            hits=[f for f in archive.infolist() if PurePosixPath(f.filename).name=='export.xml' and not f.filename.startswith('__MACOSX/')]
            if len(hits)!=1 or hits[0].file_size>MAX_EXPANDED:
                raise ValueError('Use an Apple Health ZIP with one export.xml up to 256 MiB expanded.')
            with archive.open(hits[0]) as member:
                yield from apple(member,source)
    elif ext=='.xml':
        yield from apple(stream,source)
    elif ext=='.csv' or ext=='.tsv':
        yield from csv_records(stream,source)
    elif ext=='.json':
        yield from fitbit(stream,source,filename)
    else:
        raise ValueError('Supported imports are Apple Health XML/ZIP, CSV, TSV and Fitbit JSON.')
