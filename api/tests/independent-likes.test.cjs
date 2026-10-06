// Run with Node 24+: node api/tests/independent-likes.test.cjs
// Exercise the real edge handlers against an in-memory SQLite database; no network or credentials.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { stripTypeScriptTypes } = require('node:module');
const { DatabaseSync } = require('node:sqlite');

const source = fs.readFileSync(path.join(__dirname, '..', 'edge.ts'), 'utf8');
function handler(name, end) {
    const start = source.indexOf(`async function ${name}(`);
    const stop = source.indexOf(end, start);
    assert(start >= 0 && stop > start, `Handler boundary: ${name}`);
    return source.slice(start, stop);
}

const sqlite = new DatabaseSync(':memory:');
sqlite.exec(`
    CREATE TABLE discourse (id TEXT PRIMARY KEY, total_likes INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE discourse_audio (id TEXT PRIMARY KEY, discourse_id TEXT, total_likes INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE discourse_likes (discourse_id TEXT, liked_by_user_id TEXT, PRIMARY KEY (discourse_id, liked_by_user_id));
    CREATE TABLE discourse_audio_likes (discourse_audio_id TEXT, liked_by_user_id TEXT, PRIMARY KEY (discourse_audio_id, liked_by_user_id));
    INSERT INTO discourse VALUES ('parent', 5);
    INSERT INTO discourse_audio VALUES ('audio', 'parent', 0);
`);
for (let i = 0; i < 5; i++) sqlite.prepare('INSERT INTO discourse_likes VALUES (?, ?)').run('parent', `direct-${i}`);
const db = {
    async execute(command) {
        const sql = typeof command === 'string' ? command : command.sql;
        const args = typeof command === 'string' ? [] : command.args || [];
        const statement = sqlite.prepare(sql);
        if (/^\s*SELECT/i.test(sql)) return { rows: statement.all(...args), rowsAffected: 0 };
        return { rows: [], rowsAffected: statement.run(...args).changes };
    },
    async batch(commands) { return Promise.all(commands.map(command => this.execute(command))); }
};
const code = handler('deleteUserLike', '\nconst parsePage') +
    handler('likeDiscourse', '/**\n * PUT /discourse-audios') +
    handler('likeDiscourseAudio', '/**\n * Router');
const handlers = vm.runInNewContext(stripTypeScriptTypes(code) +
    '\n({ deleteUserLike, likeDiscourse, likeDiscourseAudio })', {
    db, URL, Request, Response,
    jsonResponse: (value, status = 200) => Response.json(value, { status })
});
const count = table => Number(sqlite.prepare(`SELECT total_likes FROM ${table}`).get().total_likes);
const request = () => new Request('https://example.test/like', {
    method: 'PUT', body: JSON.stringify({ user_id: 'user' })
});

(async () => {
    let response = await handlers.likeDiscourseAudio('audio', request());
    assert.equal((await response.json()).data.total_likes, 1);
    assert.equal(count('discourse'), 5, 'Audio like must not change discourse count');
    await handlers.likeDiscourseAudio('audio', request());
    assert.equal(count('discourse_audio'), 1, 'Repeated audio like is idempotent');
    assert.equal(count('discourse'), 5);
    response = await handlers.deleteUserLike(new URL('https://example.test/likes?user_id=user&discourse_audio_id=audio'));
    const data = (await response.json()).data;
    assert.equal(data.total_likes, 0);
    assert.equal(data.unliked, true);
    assert.equal('discourse_total_likes' in data, false, 'Audio response must only expose its own count');
    assert.equal(count('discourse'), 5, 'Audio unlike must not change discourse count');
    response = await handlers.deleteUserLike(new URL('https://example.test/likes?user_id=user&discourse_audio_id=audio'));
    assert.equal((await response.json()).data.unliked, false);
    assert.equal(count('discourse'), 5);
    await handlers.likeDiscourse('parent', request());
    assert.equal(count('discourse'), 6);
    assert.equal(count('discourse_audio'), 0, 'Discourse like must not change audio count');
    await handlers.deleteUserLike(new URL('https://example.test/likes?user_id=user&discourse_id=parent'));
    assert.equal(count('discourse'), 5);
    assert.equal(count('discourse_audio'), 0, 'Discourse unlike must not change audio count');
    sqlite.exec("UPDATE discourse SET total_likes = 99");
    const repair = fs.readFileSync(path.join(__dirname, '..', 'migrations', 'independent-discourse-likes.sql'), 'utf8');
    sqlite.exec(repair);
    assert.equal(count('discourse'), 5, 'Repair counts only direct discourse likes');
    sqlite.exec(repair);
    assert.equal(count('discourse'), 5, 'Repair is idempotent');
    assert.equal(count('discourse_audio'), 0, 'Repair preserves audio totals');
    sqlite.close();
    console.log('Independent likes: like, unlike, repeat requests, and both entity counts passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
