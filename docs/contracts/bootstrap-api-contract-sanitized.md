# Bootstrap API contract extract (sanitized)

## Scope and evidence

This is a read-only extraction of the three startup endpoints in:

analysis-智能更衣柜app-20260831/智能更衣柜app对接/4接口文档/接口文档.docx

No request was sent to a server. Every displayed value below is replaced by TEST_*; no signing salt, token, credential, or real device value is included.

The DOCX renderer is unavailable here, so page numbers are not independently reliable. Locators use visible titles and table labels. The DOCX extended properties say 27 pages, but that is not treated as a verified citation.

## Common transport contract

**Locator:** 公共传输参数 -> header头部; 传参结构; the JavaScript block immediately after it; 公共返回参数说明; 错误码说明.

All three endpoint sections state HTTPS and POST. They provide only relative paths; API base URL is **UNKNOWN**.

### Headers

| Header | Required | Document type | Stated role | Status |
|---|---:|---|---|---|
| gmt-merchant-auth | Yes | string | Merchant number, obtained by device-binding check | Bootstrap applicability is **UNKNOWN** |
| device-no | Yes | string | Device number, obtained by device-binding check | Conflicts with checkDevice response type/sample |

The document does not give a header example, header-case rule, Content-Type rule, or exception for the first call. Those facts are **UNKNOWN**.

### Request envelope

| Field | Required | Document type | Meaning |
|---|---:|---|---|
| scode | Yes | string | Obtained from signing code |
| sign | Yes | string | Obtained from signing code |
| timestamp | Yes | number | Current timestamp |
| token | No | string | Required after user login |
| data | Yes | array | Every endpoint parameter is placed in data |

The adjacent signing code serializes params.data unchanged when it is a string; otherwise it calls JSON.stringify(params.data). It derives scode with MD5, then derives date-dependent SHA-1/MD5 values, then computes sign from those values, timestamp, and a fixed secret. The secret literal is deliberately not reproduced.

The code accepts string/object data even though the public table declares array. There is no endpoint request JSON example. Empty-request form (array, object, empty string, or omission), key ordering, character encoding, and timezone are **UNKNOWN**.

### Response envelope, success, and errors

| Root field | Required | Document type | Meaning |
|---|---:|---|---|
| code | Yes | number | 200=success; 400=failure; this table additionally says 2=login failure |
| message | Yes | string | Prompt/message |
| data | No | Array | Said to exist only on success |

The separate 错误码说明 table lists only 200 and 400. It does not explain code 2 or endpoint-specific errors. No non-200 JSON sample exists. Consequently non-200 data presence/type/schema is **UNKNOWN**.

Public data is optional Array, while all three success samples use a data object. Success should be identified by numeric code 200 and endpoint-specific data parsing; public Array must not be enforced for these success samples.

## 1. checkDevice

**Locator:** 是否绑定设备号接口; 接口url：/v2/central_control_screen/checkDevice; its 请求参数, 返回参数, and 返回示例.

| Item | Contract |
|---|---|
| Relative path | /v2/central_control_screen/checkDevice |
| Method / protocol | POST / HTTPS |
| Endpoint request example | **UNKNOWN**; none exists |
| Success root/data shape | object with code:number, message:string, data:object |

### Request data

Public data is declared required Array, but the endpoint table does not say whether this field is an array item or direct object member.

| Field | Required | Document type | Sample actual type | Notes |
|---|---:|---|---|---|
| device_serial | Yes | string | **UNKNOWN**; no request sample | Device serial number / SN |

### Success data

| Field | Required | Document type | Success sample actual type | Notes |
|---|---:|---|---|---|
| merchant_code | Yes | string | string | Merchant code |
| device_no | Yes | number | string | Device number |

The sample uses object data, contrary to public optional Array. device-no is string in the header table, device_no is number in the response table, and device_no is string in the sample; canonical type is **UNKNOWN**.

Both headers are marked required yet said to be obtained through this endpoint. Whether checkDevice omits them, uses pre-provisioned values, or uses another bootstrap path is **UNKNOWN**.

## 2. baseSetting

**Locator:** first section titled 基础数据; 接口url：/v2/central_control_screen/baseSetting; its request/response/sample tables.

| Item | Contract |
|---|---|
| Relative path | /v2/central_control_screen/baseSetting |
| Method / protocol | POST / HTTPS |
| Request business fields | 无; request at boot and daily at 01:00 because data may change |
| Request example | **UNKNOWN**; none exists |
| Success root/data shape | object with code:number, message:string, data:object |

There are no business request fields, but exact empty public-data representation remains **UNKNOWN**.

All data fields below are marked required by the endpoint response table.

| Field | Required | Document type | Success sample actual type | Enum / notes |
|---|---:|---|---|---|
| venue_name | Yes | string | string | Venue name |
| version | Yes | string | string | Version |
| recognition_type | Yes | Array | array of strings | "1" mobile+pickup code; "2" QR; "3" palm/palm-vein; "4" universal card/wristband; "5" face. Item type appears only in sample. |
| use_notice | Yes | Text | string | Use notice |
| return_notice | Yes | Text | string | Return notice |
| logo | Yes | string | string | URL/string constraints **UNKNOWN** |
| company | Yes | string | string | Company name |
| cozy_tips | Yes | Text | string | Tips text |
| advertisement_type | Yes | number | number | 0=carousel image; 1=video; document says only one video |
| advertisement_list | Yes | Array | array of objects | Item fields below |
| background_img | Yes | string | string | URL/string constraints **UNKNOWN** |
| palm_print_img | Yes | string | string | URL/string constraints **UNKNOWN** |
| area_list | Yes | Array | flat array of objects | Description says two-dimensional array; sample contradicts it |
| locker_check_status | Yes | number | string | Enum below; canonical numeric/string type **UNKNOWN** |

### advertisement_list item

| Field | Required | Document type | Success sample actual type | Notes |
|---|---:|---|---|---|
| image | Yes | string | string | Document says video ads also have video |
| video | Yes | string | string | Sample has empty string for an image ad |

### area_list item

| Field | Required | Document type | Success sample actual type | Notes |
|---|---:|---|---|---|
| area_id | Yes | number | number | Area ID |
| name | Yes | string | string | Area name |

### locker_check_status enum

| Value | Document meaning |
|---|---|
| 0 | Not started/in progress; only installation validation |
| 1 | Installation validation passed; only usage validation |
| 2 | Usage validation passed; locker use enabled |

The section says the client may modify this value after validation passes when it has not re-requested the endpoint. Exact local transition, rollback, and persistence are **UNKNOWN**. Its object data again conflicts with public optional Array.

## 3. basicData

**Locator:** second section titled 基础数据; 接口url：/v2/central_control_screen/basicData; its request/response/sample tables.

| Item | Contract |
|---|---|
| Relative path | /v2/central_control_screen/basicData |
| Method / protocol | POST / HTTPS |
| Request business fields | 无; data changes in real time and should be requested at home and administrator login |
| Request example | **UNKNOWN**; none exists |
| Success root/data shape | object with code:number, message:string, data:object |

There are no business request fields. Exact empty public-data representation is **UNKNOWN**.

All fields below are marked required by the endpoint response table.

| Field | Required | Document type | Success sample actual type | Enum / notes |
|---|---:|---|---|---|
| num | Yes | number | number | Total lockers |
| surplus_num | Yes | number | number | Available lockers |
| dynamic_verification_code | Yes | number | string | 0=administrator need not enter dynamic verification code; 1=must enter it. Canonical numeric/string type **UNKNOWN**. |

Its success object data conflicts with public optional Array.

## Sanitized offline parser fixtures

These preserve documented **success-response** array/object/number/string shapes. Request examples are parser fixtures based on public data:Array only; no DOCX request sample proves accepted wire payloads.

### Request-envelope fixture: checkDevice

~~~json
{
  "scode": "TEST_SCODE",
  "sign": "TEST_SIGNATURE",
  "timestamp": 1700000000,
  "data": [
    {
      "device_serial": "TEST_DEVICE_SERIAL"
    }
  ]
}
~~~

### Request-envelope fixture: empty endpoints (provisional)

~~~json
{
  "scode": "TEST_SCODE",
  "sign": "TEST_SIGNATURE",
  "timestamp": 1700000000,
  "data": []
}
~~~

Use the second fixture only as a provisional parser fixture for baseSetting/basicData. Optional token is omitted. Header fixture values, where headers are permitted, are gmt-merchant-auth: TEST_MERCHANT_CODE and device-no: TEST_DEVICE_NO; checkDevice bootstrap headers remain **UNKNOWN**.

### checkDevice success fixture

~~~json
{
  "code": 200,
  "message": "TEST_SUCCESS",
  "data": {
    "merchant_code": "TEST_MERCHANT_CODE",
    "device_no": "TEST_DEVICE_NO"
  }
}
~~~

This intentionally preserves sample string device_no, not the conflicting table type.

### baseSetting success fixture

~~~json
{
  "code": 200,
  "message": "TEST_SUCCESS",
  "data": {
    "venue_name": "TEST_VENUE_NAME",
    "version": "TEST_VERSION",
    "recognition_type": ["1", "2", "3", "4", "5"],
    "use_notice": "TEST_USE_NOTICE",
    "return_notice": "TEST_RETURN_NOTICE",
    "logo": "TEST_LOGO_URL",
    "company": "TEST_COMPANY",
    "cozy_tips": "TEST_COZY_TIPS",
    "advertisement_type": 0,
    "advertisement_list": [
      {
        "image": "TEST_AD_IMAGE_URL",
        "video": ""
      }
    ],
    "background_img": "TEST_BACKGROUND_IMAGE_URL",
    "palm_print_img": "TEST_PALM_PRINT_IMAGE_URL",
    "area_list": [
      {
        "area_id": 2,
        "name": "TEST_AREA_NAME"
      }
    ],
    "locker_check_status": "0"
  }
}
~~~

This preserves sample string recognition codes, string locker_check_status, flat area_list, and string video.

### basicData success fixture

~~~json
{
  "code": 200,
  "message": "TEST_SUCCESS",
  "data": {
    "num": 0,
    "surplus_num": 0,
    "dynamic_verification_code": "1"
  }
}
~~~

This preserves sample string dynamic_verification_code, despite table type number.

### Non-200 fixture policy

No document-faithful non-200 JSON sample exists. A test may use this **synthetic** minimal failure fixture, but must not assert it is a server sample:

~~~json
{
  "code": 400,
  "message": "TEST_FAILURE"
}
~~~

data is absent because public response data is optional and said to exist only on success. Real failure data presence/type/schema remain **UNKNOWN**.

## Explicitly deferred to server owner

1. Base URLs and TLS-pinning policy.
2. checkDevice bootstrap headers and device_serial source/format/lifecycle.
3. Empty data representation for baseSetting/basicData.
4. Serialization charset/key ordering, date timezone, and timestamp tolerance for signature verification.
5. Request Content-Type, header case, token location, and timeout/retry/cancellation/idempotency behavior.
6. Complete error-code catalogue and non-200 schemas.
7. Canonical types for device_no, locker_check_status, dynamic_verification_code, and canonical dimension of area_list.
