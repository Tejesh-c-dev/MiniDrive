import { describe, expect, it } from 'vitest';
import { ResumeStore, isExpired } from '@/uploads/resumeStore';
import { makeFile, makeRecord, memoryStorage } from './__tests__/testSupport';

describe('ResumeStore', () => {
  it('saves, reads, lists and removes records', () => {
    const store = new ResumeStore(memoryStorage());
    const record = makeRecord();
    store.save(record);

    expect(store.get(record.sessionId)).toEqual(record);
    expect(store.list()).toHaveLength(1);

    store.remove(record.sessionId);
    expect(store.get(record.sessionId)).toBeNull();
    expect(store.list()).toEqual([]);
  });

  it('returns records newest-first', () => {
    const store = new ResumeStore(memoryStorage());
    store.save(
      makeRecord({
        sessionId: 'old',
        savedAt: '2026-01-01T00:00:00.000Z',
        expiresAt: '2030-01-01T00:00:00.000Z',
      }),
    );
    store.save(
      makeRecord({
        sessionId: 'new',
        savedAt: '2026-02-01T00:00:00.000Z',
        expiresAt: '2030-01-01T00:00:00.000Z',
      }),
    );

    expect(store.list().map((record) => record.sessionId)).toEqual([
      'new',
      'old',
    ]);
  });

  it('prunes expired records instead of returning them', () => {
    const store = new ResumeStore(memoryStorage());
    store.save(
      makeRecord({
        sessionId: 'stale',
        expiresAt: new Date(Date.now() - 1000).toISOString(),
      }),
    );

    expect(store.list()).toEqual([]);
    expect(store.get('stale')).toBeNull();
  });

  it('finds a record by file identity and prefers a matching lastModified', () => {
    const store = new ResumeStore(memoryStorage());
    store.save(
      makeRecord({
        sessionId: 'first',
        filename: 'report.pdf',
        sizeBytes: 2048,
        lastModified: 111,
      }),
    );
    store.save(
      makeRecord({
        sessionId: 'second',
        filename: 'report.pdf',
        sizeBytes: 2048,
        lastModified: 222,
        savedAt: '2030-01-01T00:00:00.000Z',
      }),
    );

    expect(store.findForFile(makeFile('report.pdf', 2048, 'application/pdf', 222))?.sessionId).toBe(
      'second',
    );
    expect(store.findForFile(makeFile('report.pdf', 2048, 'application/pdf', 999))?.sessionId).toBe(
      'second',
    );
    expect(store.findForFile(makeFile('other.pdf', 2048))).toBeNull();
    expect(store.findForFile(makeFile('report.pdf', 1))).toBeNull();
  });

  it('degrades gracefully when storage is unavailable or corrupted', () => {
    const broken = new ResumeStore({
      getItem: () => '{not json',
      setItem: () => {
        throw new Error('quota exceeded');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    });

    expect(broken.list()).toEqual([]);
    expect(broken.get('anything')).toBeNull();
    expect(() => broken.save(makeRecord())).not.toThrow();
    expect(() => broken.remove('anything')).not.toThrow();

    const disabled = new ResumeStore(null);
    disabled.save(makeRecord());
    expect(disabled.list()).toEqual([]);
  });

  it('never persists authentication tokens', () => {
    const storage = memoryStorage();
    const store = new ResumeStore(storage);
    store.save(makeRecord());
    const persisted = storage.getItem('minidrive_resumable_uploads') ?? '';
    expect(persisted).not.toMatch(/token|authorization|bearer/i);
  });
});

describe('isExpired', () => {
  it('compares the stored expiry against the current time', () => {
    const now = Date.parse('2026-06-01T00:00:00.000Z');
    expect(isExpired(makeRecord({ expiresAt: '2026-05-31T23:59:59.000Z' }), now)).toBe(true);
    expect(isExpired(makeRecord({ expiresAt: '2026-06-01T00:00:01.000Z' }), now)).toBe(false);
  });
});
