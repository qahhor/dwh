import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { CommandPaletteService } from './command-palette.service';
import { ToastService } from './toast.service';

describe('Global search feedback ownership', () => {
  it('returns the failure to the search dialog without adding a duplicate toast', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    let failure: unknown;
    TestBed.inject(CommandPaletteService).search('missing').subscribe({ error: error => failure = error });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(request => request.url === '/api/v1/search')
      .flush({ detail: 'Search temporarily unavailable' }, { status: 503, statusText: 'Unavailable' });

    expect(failure).toMatchObject({ detail: 'Search temporarily unavailable', status: 503 });
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
    http.verify();
  });
});
