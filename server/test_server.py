import base64
import importlib.util
import json
import os
import io
import threading
import urllib.request
import urllib.error
import zipfile
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace


class ParserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.path = Path(__file__).with_name('server.py')

    def api(self):
        self.assertTrue(self.path.exists(), 'server implementation is missing')
        import server
        return server

    def test_week_gaps_and_merge(self):
        s = self.api()
        data = '节次,,星期一\n,一,数学/(1-1节)1-3周，5周/ A101/王老师\n,二,数学/(2-2节)1-3周，5周/ A101/王老师'.encode()
        result = s.parse_request({'filename':'a.csv','contentBase64':base64.b64encode(data).decode(),'mode':'rules'})
        self.assertEqual(len(result['courses']), 1)
        self.assertEqual(result['courses'][0]['weeks'], [1,2,3,5])
        self.assertEqual(result['courses'][0]['end'], 2)
        self.assertEqual(result['semester']['startDate'], '')

    def test_synthetic_sample(self):
        s = self.api()
        path = Path(__file__).parent / 'fixtures' / 'synthetic.csv'
        result = s.parse_request({'filename':path.name,'contentBase64':base64.b64encode(path.read_bytes()).decode(),'mode':'rules'})
        self.assertEqual(len(result['courses']),1)
        self.assertEqual(result['semester']['startDate'],'')
        self.assertEqual(len(result['pending']),0)
        course = result['courses'][0]
        self.assertEqual(course['title'],'测试课程')
        self.assertEqual(course['weeks'],[1,2,3,5])
        self.assertEqual((course['start'],course['end'],course['day']),(1,2,1))

    def test_invalid_requests(self):
        s=self.api()
        for request in ({}, {'filename':'x.exe','contentBase64':'AA==','mode':'rules'}, {'filename':'x.csv','contentBase64':'?','mode':'rules'}, {'filename':'x.jpg','contentBase64':'AA==','mode':'rules'}):
            with self.assertRaises(s.ApiError): s.parse_request(request)

    def test_ai_validation(self):
        s=self.api()
        course={'title':'数学','teacher':'','room':'','day':1,'start':1,'end':2,'weeks':[1,3],'color':'#123456','notes':''}
        valid={'courses':[course],'pending':[],'semester':{'name':'','startDate':'','totalWeeks':20},'warnings':[]}
        self.assertEqual(s.validate_result(valid,'ai')['courses'][0]['weeks'],[1,3])
        for key,value in [('day',True),('weeks',[0]),('end',17),('title',42)]:
            invalid=json.loads(json.dumps(valid)); invalid['courses'][0][key]=value
            with self.assertRaises(s.ApiError): s.validate_result(invalid,'ai')
        request={'filename':'x.csv','contentBase64':base64.b64encode(b'a,b').decode(),'mode':'ai'}
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaises(s.ApiError):s.parse_request(request)
        with patch.dict(os.environ, {'AI_API_URL':'https://example.com/v1/chat/completions','AI_API_KEY':'test','AI_MODEL':'test'}), patch.object(s,'call_ai',return_value=valid):
            self.assertEqual(s.parse_request(request)['mode'],'ai')

    def test_xlsx_inline_strings_and_merges(self):
        s=self.api(); buffer=io.BytesIO()
        with zipfile.ZipFile(buffer,'w') as z:
            z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="C1" t="inlineStr"><is><t>星期一</t></is></c></row><row r="2"><c r="C2" t="inlineStr"><is><t>数学/(1-2节)1-3周/教1/王老师</t></is></c></row></sheetData><mergeCells><mergeCell ref="C2:C3"/></mergeCells></worksheet>')
        sheets=s.read_sheets(buffer.getvalue(),'.xlsx')
        self.assertEqual(sheets[0]['merges'],['C2:C3'])
        self.assertEqual(s.parse_rules(sheets)['courses'][0]['end'],2)

    def test_http_health_and_errors(self):
        s=self.api()
        with s.ThreadingHTTPServer(('127.0.0.1',0),s.Handler) as http:
            thread=threading.Thread(target=http.serve_forever,daemon=True); thread.start()
            base='http://127.0.0.1:'+str(http.server_port)
            try:
                with urllib.request.urlopen(base+'/health') as response:
                    self.assertEqual(json.load(response)['status'],'ok')
                body=json.dumps({'filename':'a.csv','contentBase64':base64.b64encode('星期一\n数学/(1-2节)1-3周/教1/老师'.encode()).decode(),'mode':'rules'}).encode()
                with urllib.request.urlopen(urllib.request.Request(base+'/parse',body,{'Content-Type':'application/json'})) as response:
                    self.assertEqual(json.load(response)['courses'][0]['title'],'数学')
                for body,expected in [(b'{',400),(json.dumps({'filename':'a.exe','contentBase64':'AA==','mode':'rules'}).encode(),400)]:
                    with self.assertRaises(urllib.error.HTTPError) as raised:
                        urllib.request.urlopen(urllib.request.Request(base+'/parse',body,{'Content-Type':'application/json'}))
                    self.assertEqual(raised.exception.code,expected)
                    self.assertIn('error',json.load(raised.exception))
            finally: http.shutdown(); thread.join()

    def test_ai_transport_image_and_invalid_json(self):
        s=self.api()
        result={'courses':[],'pending':[],'semester':{'name':'','startDate':'','totalWeeks':20},'warnings':[]}
        class Response(io.BytesIO):
            def __enter__(self): return self
            def __exit__(self,*args): self.close()
        class Opener:
            def open(self,request,timeout):
                self.request=json.loads(request.data)
                return Response(json.dumps({'choices':[{'message':{'content':json.dumps(result)}}]}).encode())
        opener=Opener()
        env={'AI_API_URL':'https://example.com/v1/chat/completions','AI_API_KEY':'test','AI_MODEL':'vision'}
        with patch.dict(os.environ,env),patch.object(s.urllib.request,'build_opener',return_value=opener):
            actual=s.parse_request({'filename':'image.png','contentBase64':base64.b64encode(b'\x89PNG\r\n\x1a\nTEST').decode(),'mode':'ai'})
            self.assertEqual(actual['mode'],'ai')
            self.assertTrue(opener.request['messages'][1]['content'][1]['image_url']['url'].startswith('data:image/png;base64,'))
        with patch.dict(os.environ,env),patch.object(s,'call_ai',return_value={'courses':42}):
            with self.assertRaises(s.ApiError): s.parse_request({'filename':'x.csv','contentBase64':'YSxi','mode':'ai'})

    def test_reject_oversize_and_xml_entities(self):
        s=self.api()
        with patch.object(s,'MAX_FILE',2):
            with self.assertRaises(s.ApiError) as error:
                s.parse_request({'filename':'x.csv','contentBase64':base64.b64encode(b'abc').decode(),'mode':'rules'})
            self.assertEqual(error.exception.status,413)
        buffer=io.BytesIO()
        with zipfile.ZipFile(buffer,'w') as z:
            z.writestr('xl/worksheets/sheet1.xml','<!DOCTYPE x [<!ENTITY a "x">]><worksheet/>')
        with self.assertRaises(s.ApiError): s.read_sheets(buffer.getvalue(),'.xlsx')

    def test_unknown_weekday_preserves_course(self):
        s=self.api()
        for header in ('Monday','未知星期',''):
            result=s.parse_rules([{'name':'表1','rows':[[header],['数学/(1-2节)1-3周/教1/王老师']],'merges':[]}])
            self.assertEqual(result['courses'],[])
            self.assertEqual(len(result['pending']),1)
            self.assertEqual(result['pending'][0]['title'],'数学')
            self.assertIn('星期',result['pending'][0]['notes'])

    def test_xlsx_aggregate_cell_and_sheet_limits(self):
        s=self.api()
        def workbook(count):
            buffer=io.BytesIO()
            with zipfile.ZipFile(buffer,'w') as z:
                for i in range(count):
                    z.writestr('xl/worksheets/sheet'+str(i+1)+'.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="10"><c r="J10"><v>1</v></c></row></sheetData></worksheet>')
            return buffer.getvalue()
        with patch.object(s,'MAX_DENSE_CELLS',150,create=True):
            with self.assertRaises(s.ApiError): s.read_sheets(workbook(2),'.xlsx')
        with patch.object(s,'MAX_SHEETS',1,create=True):
            with self.assertRaises(s.ApiError): s.read_sheets(workbook(2),'.xlsx')

    def test_xls_aggregate_budget_before_row_allocation(self):
        s=self.api(); reads=[]
        class Sheet:
            nrows=10; ncols=10; name='test'; merged_cells=[]
            def row_values(self,row): reads.append(row); return ['']*10
        class Book:
            nsheets=2
            def sheets(self): return [Sheet(),Sheet()]
            def sheet_by_index(self,index): return Sheet()
            def unload_sheet(self,index): pass
            def release_resources(self): pass
        with patch.dict('sys.modules',{'xlrd':SimpleNamespace(open_workbook=lambda **kwargs:Book())}),patch.object(s,'MAX_DENSE_CELLS',150,create=True):
            with self.assertRaises(s.ApiError): s.read_sheets(b'test','.xls')
        self.assertLessEqual(len(reads),10,'over-budget sheet must not allocate rows')
        reads.clear()
        with patch.dict('sys.modules',{'xlrd':SimpleNamespace(open_workbook=lambda **kwargs:Book())}),patch.object(s,'MAX_SHEETS',1,create=True):
            with self.assertRaises(s.ApiError): s.read_sheets(b'test','.xls')
        self.assertEqual(reads,[])

if __name__=='__main__': unittest.main()
