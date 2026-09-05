import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiPaginationComponent } from './ui-pagination.component';

describe('UiPaginationComponent', () => {
  it('exposes navigation, current page and named icon controls', async () => {
    await TestBed.configureTestingModule({ imports: [UiPaginationComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiPaginationComponent);
    fixture.componentRef.setInput('totalItems', 120);
    fixture.componentRef.setInput('currentPage', 2);
    fixture.componentRef.setInput('pageSize', 10);
    fixture.detectChanges();

    const navigation = fixture.nativeElement.querySelector('nav[aria-label="Пагинация"]') as HTMLElement;
    const currentPage = fixture.nativeElement.querySelector('[aria-current="page"]') as HTMLButtonElement;
    const nextPage = fixture.nativeElement.querySelector('button[aria-label="Следующая страница"]') as HTMLButtonElement;

    expect(navigation).not.toBeNull();
    expect(currentPage.textContent?.trim()).toBe('2');
    expect(nextPage.type).toBe('button');
    expect(nextPage.querySelector('[aria-hidden="true"]')).not.toBeNull();
  });

  it('associates the page-size label and announces the visible range', async () => {
    await TestBed.configureTestingModule({ imports: [UiPaginationComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiPaginationComponent);
    fixture.componentRef.setInput('totalItems', 25);
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    const label = fixture.nativeElement.querySelector(`label[for="${select.id}"]`) as HTMLLabelElement;
    const range = fixture.nativeElement.querySelector('[role="status"][aria-live="polite"]') as HTMLElement;

    expect(select.id).not.toBe('');
    expect(label.textContent).toContain('Строк');
    expect(range.textContent).toContain('1–10');
  });

  it('uses sequential previous and next controls in cursor mode', async () => {
    await TestBed.configureTestingModule({ imports: [UiPaginationComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiPaginationComponent);
    fixture.componentRef.setInput('totalItems', 120);
    fixture.componentRef.setInput('currentPage', 2);
    fixture.componentRef.setInput('pageSize', 20);
    fixture.componentRef.setInput('cursorMode', true);
    fixture.componentRef.setInput('hasNextPage', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('button[aria-label="Первая страница"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('button[aria-label="Последняя страница"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.page-numbers')).toBeNull();
    expect((fixture.nativeElement.querySelector('button[aria-label="Предыдущая страница"]') as HTMLButtonElement).disabled).toBe(false);
    expect((fixture.nativeElement.querySelector('button[aria-label="Следующая страница"]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('does not clamp cursor pages to a local item count or present it as a total', async () => {
    await TestBed.configureTestingModule({ imports: [UiPaginationComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiPaginationComponent);
    fixture.componentRef.setInput('totalItems', 25);
    fixture.componentRef.setInput('currentPage', 3);
    fixture.componentRef.setInput('pageSize', 50);
    fixture.componentRef.setInput('cursorMode', true);
    fixture.componentRef.setInput('hasNextPage', false);
    fixture.detectChanges();

    const emitted: number[] = [];
    fixture.componentInstance.pageChange.subscribe(page => emitted.push(page));
    const previous = fixture.nativeElement.querySelector('button[aria-label="Предыдущая страница"]') as HTMLButtonElement;
    previous.click();

    expect(fixture.componentInstance.currentPage).toBe(3);
    expect(emitted).toEqual([2]);
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('101–125');
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).not.toContain('из 25');
  });

  it('keeps cursor navigation visible for an empty later page', async () => {
    await TestBed.configureTestingModule({ imports: [UiPaginationComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiPaginationComponent);
    fixture.componentRef.setInput('totalItems', 0);
    fixture.componentRef.setInput('currentPage', 2);
    fixture.componentRef.setInput('pageSize', 50);
    fixture.componentRef.setInput('cursorMode', true);
    fixture.componentRef.setInput('hasNextPage', false);
    fixture.detectChanges();

    const previous = fixture.nativeElement.querySelector('button[aria-label="Предыдущая страница"]') as HTMLButtonElement;
    expect(previous).not.toBeNull();
    expect(previous.disabled).toBe(false);
  });
});
