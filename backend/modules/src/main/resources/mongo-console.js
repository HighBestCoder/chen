/* Only __execute crosses the process boundary; every expanded command is checked by Chen. */
'use strict';
const __stringify = JSON.stringify.bind(JSON);
const __parse = JSON.parse.bind(JSON);
const __logs = [];
let __last;
function ObjectId(value) {
  if (!(this instanceof ObjectId)) return new ObjectId(value);
  if (value === undefined) value=__newObjectId();
  this.$oid = String(value);
}
ObjectId.prototype.toString = function () { return this.$oid; };
ObjectId.prototype.toHexString = ObjectId.prototype.toString;
function NumberLong(value) {
  if (!(this instanceof NumberLong)) return new NumberLong(value);
  this.$numberLong = String(value);
}
NumberLong.prototype.toString = function () { return this.$numberLong; };
NumberLong.prototype.valueOf = function () { return BigInt(this.$numberLong); };
function NumberDecimal(value) {
  if (!(this instanceof NumberDecimal)) return new NumberDecimal(value);
  this.$numberDecimal = String(value);
}
NumberDecimal.prototype.toString = function () { return this.$numberDecimal; };
function NumberInt(value) { return Number(value); }
function ISODate(value) { return new Date(value === undefined ? Date.now() : value); }
function BinData(type, value) { return {$binary: {base64: String(value), subType: Number(type).toString(16).padStart(2, '0')}}; }
function __bson(value) {
  return __stringify(value, function(key, current) {
    const original = this[key];
    if (original instanceof Date) return {$date: original.toISOString()};
    if (original instanceof RegExp) return {$regularExpression: {pattern: original.source, options: original.flags}};
    if (typeof current === 'bigint') return {$numberLong: String(current)};
    return current;
  });
}
function __decode(json) {
  return __parse(json, (key, value) => {
    if (!value || typeof value !== 'object') return value;
    if (Object.keys(value).length === 1) {
      if ('$oid' in value) return ObjectId(value.$oid);
      if ('$numberLong' in value) return NumberLong(value.$numberLong);
      if ('$numberDecimal' in value) return NumberDecimal(value.$numberDecimal);
      if ('$numberInt' in value) return Number(value.$numberInt);
      if ('$numberDouble' in value) return Number(value.$numberDouble);
      if ('$date' in value) return new Date(typeof value.$date === 'object' ? Number(value.$date.$numberLong) : value.$date);
    }
    return value;
  });
}
function __call(database, collection, method, args, modifiers = []) {
  const prefix = collection === null ? 'db' : 'db.getCollection(' + __bson(collection) + ')';
  const command = prefix + '.' + method + '(' + args.map(__bson).join(',') + ')' + modifiers.map(([name, value]) => '.' + name + '(' + __bson(value) + ')').join('');
  const result = __decode(__execute(command, database));
  if (result.error) throw Error(result.error);
  __last = result.value;
  return result.value;
}
class __Cursor {
  constructor(database, collection, method, args) { this.database=database;this.collection=collection;this.method=method;this.args=args;this.modifiers=[];this.rows=null;this.position=0; }
  _modify(name, value) { if(this.rows !== null)throw Error('Cursor already executed');this.modifiers.push([name,value]);return this; }
  sort(value) { return this._modify('sort',value); }
  limit(value) { return this._modify('limit',value); }
  skip(value) { return this._modify('skip',value); }
  hint(value) { return this._modify('hint',value); }
  collation(value) { return this._modify('collation',value); }
  maxTimeMS(value) { return this._modify('maxTimeMS',value); }
  batchSize(value) { return this._modify('batchSize',value); }
  toArray() { if(this.rows===null)this.rows=__call(this.database,this.collection,this.method,this.args,this.modifiers);return this.rows; }
  forEach(callback) { this.toArray().forEach(callback); }
  map(callback) { return this.toArray().map(callback); }
  hasNext() { return this.position<this.toArray().length; }
  next() { if(!this.hasNext())throw Error('Cursor exhausted');return this.rows[this.position++]; }
  [Symbol.iterator]() { return this.toArray()[Symbol.iterator](); }
}
function __collection(database,name) {
  return new Proxy(Object.create(null), {get(_,method) {
    if (method === 'then' || typeof method === 'symbol') return undefined;
    if (method === 'getName') return () => name;
    if (method === 'getFullName') return () => database + '.' + name;
    return (...args) => method==='find'||method==='aggregate' ? new __Cursor(database,name,method,args) : __call(database,name,method,args);
  }});
}
function __db(name) {
  return new Proxy(Object.create(null), {get(_,property) {
    if (property === 'then' || typeof property === 'symbol') return undefined;
    if (property === 'getName') return () => name;
    if (property === 'getSiblingDB') return other => __db(String(other));
    if (property === 'getCollection') return collection => __collection(name,String(collection));
    if (['runCommand','createCollection','dropDatabase','stats'].includes(property)) return (...args) => __call(name,null,property,args);
    return __collection(name,property);
  }});
}
const db = __db(__databaseName);
function print(...values) { if(__logs.length>=1000)throw Error('Print limit exceeded');__logs.push(values.map(v=>typeof v==='string'?v:__bson(v)).join(' ')); }
const printjson = print;
function __finish(value) {
  if (value instanceof __Cursor) value=value.toArray();
  if (value === undefined) value=__last === undefined ? null : __last;
  return __bson({value, output:__logs});
}
